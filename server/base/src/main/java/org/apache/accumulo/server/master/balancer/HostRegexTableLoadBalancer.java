/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.server.master.balancer;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.impl.KeyExtent;
import org.apache.accumulo.core.master.thrift.TabletServerStatus;
import org.apache.accumulo.core.tabletserver.thrift.TabletStats;
import org.apache.accumulo.server.conf.ServerConfiguration;
import org.apache.accumulo.server.master.state.TServerInstance;
import org.apache.accumulo.server.master.state.TabletMigration;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This balancer creates groups of tablet servers using user-provided regular expressions over the tablet server hostnames. Then it delegates to the table
 * balancer to balance the tablets within the resulting group of tablet servers. All tablet servers that do not match a regex are grouped into a default group.<br>
 * Regex properties for this balancer are specified as:<br>
 * <b>table.custom.balancer.host.regex.&lt;tablename&gt;=&lt;regex&gt;</b><br>
 * Periodically (default 5m) this balancer will check to see if a tablet server is hosting tablets that it should not be according to the regex configuration.
 * If this occurs then the offending tablets will be reassigned. This would cover the case where the configuration is changed and the master is restarted while
 * the tablet servers are up. To change the out of bounds check time period, set the following property:<br>
 * <b>table.custom.balancer.host.regex.oob.period=5m</b><br>
 * Periodically (default 1m) this balancer will regroup the set of current tablet servers into pools based on regexes applied to the tserver host names. This
 * would cover the case of tservers dying or coming online. To change the host pool check time period, set the following property: <br>
 * <b>table.custom.balancer.host.regex.pool.check=5m</b><br>
 * Regex matching can be based on either the host name (default) or host ip address. To set this balancer to match the regular expressions to the tablet server
 * IP address, then set the following property:<br>
 * <b>table.custom.balancer.host.regex.is.ip=true</b>
 *
 */
public class HostRegexTableLoadBalancer extends TableLoadBalancer {

  private static final Logger LOG = LoggerFactory.getLogger(HostRegexTableLoadBalancer.class);
  public static final String HOST_BALANCER_PREFIX = Property.TABLE_ARBITRARY_PROP_PREFIX.getKey() + "balancer.host.regex.";
  public static final String HOST_BALANCER_OOB_CHECK = Property.TABLE_ARBITRARY_PROP_PREFIX.getKey() + "balancer.host.regex.oob.period";
  private static final String HOST_BALANCER_OOB_DEFAULT = "5m";
  public static final String HOST_BALANCER_POOL_RECHECK_KEY = Property.TABLE_ARBITRARY_PROP_PREFIX.getKey() + "balancer.host.regex.pool.check";
  private static final String HOST_BALANCER_POOL_RECHECK_DEFAULT = "1m";
  public static final String HOST_BALANCER_REGEX_USING_IPS = Property.TABLE_ARBITRARY_PROP_PREFIX.getKey() + "balancer.host.regex.is.ip";
  protected static final String DEFAULT_POOL = "HostTableLoadBalancer.ALL";

  protected long oobCheckMillis = AccumuloConfiguration.getTimeInMillis(HOST_BALANCER_OOB_DEFAULT);
  protected long poolRecheckMillis = AccumuloConfiguration.getTimeInMillis(HOST_BALANCER_POOL_RECHECK_DEFAULT);

  private Map<String,String> tableIdToTableName = null;
  private Map<String,Pattern> poolNameToRegexPattern = null;
  private volatile long lastOOBCheck = System.currentTimeMillis();
  private volatile long lastPoolRecheck = 0;
  private boolean isIpBasedRegex = false;
  private Map<String,SortedMap<TServerInstance,TabletServerStatus>> pools = new HashMap<String,SortedMap<TServerInstance,TabletServerStatus>>();

  /**
   * Group the set of current tservers by pool name. Tservers that don't match a regex are put into a default pool. This could be expensive in the terms of the
   * amount of time to recompute the groups, so HOST_BALANCER_POOL_RECHECK_KEY should be specified in the terms of minutes, not seconds or less.
   *
   * @param current
   *          map of current tservers
   * @return current servers grouped by pool name, if not a match it is put into a default pool.
   */
  protected synchronized Map<String,SortedMap<TServerInstance,TabletServerStatus>> splitCurrentByRegex(SortedMap<TServerInstance,TabletServerStatus> current) {
    if ((System.currentTimeMillis() - lastPoolRecheck) > poolRecheckMillis) {
      LOG.debug("Performing pool recheck - regrouping tablet servers based on regular expressions");
      Map<String,SortedMap<TServerInstance,TabletServerStatus>> newPools = new HashMap<String,SortedMap<TServerInstance,TabletServerStatus>>();
      for (Entry<TServerInstance,TabletServerStatus> e : current.entrySet()) {
        List<String> poolNames = getPoolNamesForHost(e.getKey().host());
        for (String pool : poolNames) {
          SortedMap<TServerInstance,TabletServerStatus> np = newPools.get(pool);
          if (null == np) {
            np = new TreeMap<TServerInstance,TabletServerStatus>(current.comparator());
            newPools.put(pool, np);
          }
          np.put(e.getKey(), e.getValue());
        }
      }
      pools = newPools;
      this.lastPoolRecheck = System.currentTimeMillis();
    }
    return pools;
  }

  /**
   * Matches host against the regexes and returns the matching pool names
   *
   * @param host
   *          tablet server host
   * @return pool names, will return default pool if host matches more no regex
   */
  protected List<String> getPoolNamesForHost(String host) {
    String test = host;
    if (!isIpBasedRegex) {
      try {
        test = getNameFromIp(host);
      } catch (UnknownHostException e1) {
        LOG.error("Unable to determine host name for IP: " + host + ", setting to default pool", e1);
        return Collections.singletonList(DEFAULT_POOL);
      }
    }
    List<String> pools = new ArrayList<>();
    for (Entry<String,Pattern> e : poolNameToRegexPattern.entrySet()) {
      if (e.getValue().matcher(test).matches()) {
        pools.add(e.getKey());
      }
    }
    if (pools.size() == 0) {
      pools.add(DEFAULT_POOL);
    }
    return pools;
  }

  protected String getNameFromIp(String hostIp) throws UnknownHostException {
    return InetAddress.getByName(hostIp).getHostName();
  }

  /**
   * Matches table name against pool names, returns matching pool name or DEFAULT_POOL.
   *
   * @param tableName
   *          name of table
   * @return tablet server pool name (table name or DEFAULT_POOL)
   */
  protected String getPoolNameForTable(String tableName) {
    if (null == tableName) {
      return DEFAULT_POOL;
    }
    return poolNameToRegexPattern.containsKey(tableName) ? tableName : DEFAULT_POOL;
  }

  /**
   * Parse configuration and extract properties
   *
   * @param conf
   *          server configuration
   */
  protected void parseConfiguration(ServerConfiguration conf) {
    TableOperations t = getTableOperations();
    if (null == t) {
      throw new RuntimeException("Table Operations cannot be null");
    }
    tableIdToTableName = new HashMap<>();
    poolNameToRegexPattern = new HashMap<>();
    for (Entry<String,String> table : t.tableIdMap().entrySet()) {
      tableIdToTableName.put(table.getValue(), table.getKey());
      Map<String,String> customProps = conf.getTableConfiguration(table.getValue()).getAllPropertiesWithPrefix(Property.TABLE_ARBITRARY_PROP_PREFIX);
      if (null != customProps && customProps.size() > 0) {
        for (Entry<String,String> customProp : customProps.entrySet()) {
          if (customProp.getKey().startsWith(HOST_BALANCER_PREFIX)) {
            String tableName = customProp.getKey().substring(HOST_BALANCER_PREFIX.length());
            String regex = customProp.getValue();
            poolNameToRegexPattern.put(tableName, Pattern.compile(regex));
          }
        }
      }
    }
    String oobProperty = conf.getConfiguration().get(HOST_BALANCER_OOB_CHECK);
    if (null != oobProperty) {
      oobCheckMillis = AccumuloConfiguration.getTimeInMillis(oobProperty);
    }
    String poolRecheckProperty = conf.getConfiguration().get(HOST_BALANCER_POOL_RECHECK_KEY);
    if (null != poolRecheckProperty) {
      poolRecheckMillis = AccumuloConfiguration.getTimeInMillis(poolRecheckProperty);
    }
    String ipBased = conf.getConfiguration().get(HOST_BALANCER_REGEX_USING_IPS);
    if (null != ipBased) {
      isIpBasedRegex = Boolean.parseBoolean(ipBased);
    }
  }

  public Map<String,String> getTableIdToTableName() {
    return tableIdToTableName;
  }

  public Map<String,Pattern> getPoolNameToRegexPattern() {
    return poolNameToRegexPattern;
  }

  public long getOobCheckMillis() {
    return oobCheckMillis;
  }

  public long getPoolRecheckMillis() {
    return poolRecheckMillis;
  }

  public boolean isIpBasedRegex() {
    return isIpBasedRegex;
  }

  @Override
  public void init(ServerConfiguration conf) {
    super.init(conf);
    parseConfiguration(conf);
  }

  @Override
  public void getAssignments(SortedMap<TServerInstance,TabletServerStatus> current, Map<KeyExtent,TServerInstance> unassigned,
      Map<KeyExtent,TServerInstance> assignments) {

    Map<String,SortedMap<TServerInstance,TabletServerStatus>> pools = splitCurrentByRegex(current);
    // group the unassigned into tables
    Map<String,Map<KeyExtent,TServerInstance>> groupedUnassigned = new HashMap<String,Map<KeyExtent,TServerInstance>>();
    for (Entry<KeyExtent,TServerInstance> e : unassigned.entrySet()) {
      Map<KeyExtent,TServerInstance> tableUnassigned = groupedUnassigned.get(e.getKey().getTableId());
      if (tableUnassigned == null) {
        tableUnassigned = new HashMap<KeyExtent,TServerInstance>();
        groupedUnassigned.put(e.getKey().getTableId(), tableUnassigned);
      }
      tableUnassigned.put(e.getKey(), e.getValue());
    }
    // Send a view of the current servers to the tables tablet balancer
    for (Entry<String,Map<KeyExtent,TServerInstance>> e : groupedUnassigned.entrySet()) {
      Map<KeyExtent,TServerInstance> newAssignments = new HashMap<KeyExtent,TServerInstance>();
      String tableName = tableIdToTableName.get(e.getKey());
      String poolName = getPoolNameForTable(tableName);
      SortedMap<TServerInstance,TabletServerStatus> currentView = pools.get(poolName);
      if (null == currentView || currentView.size() == 0) {
        LOG.warn("No tablet servers online for table {}, assigning within default pool", tableName);
        currentView = pools.get(DEFAULT_POOL);
        if (null == currentView) {
          LOG.error("No tablet servers exist in the default pool, unable to assign tablets for table {}", tableName);
          continue;
        }
      }
      LOG.debug("Sending {} tablets to balancer for table {} for assignment within tservers {}", e.getValue().size(), tableName, currentView.keySet());
      getBalancerForTable(e.getKey()).getAssignments(currentView, e.getValue(), newAssignments);
      assignments.putAll(newAssignments);
    }
  }

  @Override
  public long balance(SortedMap<TServerInstance,TabletServerStatus> current, Set<KeyExtent> migrations, List<TabletMigration> migrationsOut) {
    long minBalanceTime = 5 * 1000;
    // Iterate over the tables and balance each of them
    TableOperations t = getTableOperations();
    if (t == null)
      return minBalanceTime;

    Map<String,SortedMap<TServerInstance,TabletServerStatus>> currentGrouped = splitCurrentByRegex(current);
    if ((System.currentTimeMillis() - this.lastOOBCheck) > this.oobCheckMillis) {
      try {
        // Check to see if a tablet is assigned outside the bounds of the pool. If so, migrate it.
        for (Entry<TServerInstance,TabletServerStatus> e : current.entrySet()) {
          for (String assignedPool : getPoolNamesForHost(e.getKey().host())) {
            for (String table : poolNameToRegexPattern.keySet()) {
              // pool names are the same as table names, except in the DEFAULT case.
              if (assignedPool.equals(table)) {
                // If this tserver is assigned to a regex pool, then we can skip checking tablets for this table on this host.
                continue;
              }
              String tid = t.tableIdMap().get(table);
              if (null == tid) {
                LOG.warn("Unable to check for out of bounds tablets for table {}, it may have been deleted or renamed.", table);
                continue;
              }
              try {
                List<TabletStats> outOfBoundsTablets = getOnlineTabletsForTable(e.getKey(), tid);
                for (TabletStats ts : outOfBoundsTablets) {
                  KeyExtent ke = new KeyExtent(ts.getExtent());
                  if (migrations.contains(ke)) {
                    LOG.debug("Migration for  out of bounds tablet {} has already been requested", ke);
                    ;
                    continue;
                  }
                  String poolName = getPoolNameForTable(table);
                  SortedMap<TServerInstance,TabletServerStatus> currentView = currentGrouped.get(poolName);
                  if (null != currentView) {
                    TServerInstance nextTS = currentView.firstKey();
                    LOG.info("Tablet {} is currently outside the bounds of the regex, migrating from {} to {}", ke, e.getKey(), nextTS);
                    migrationsOut.add(new TabletMigration(ke, e.getKey(), nextTS));
                  } else {
                    LOG.warn("No tablet servers online for pool {}, unable to migrate out of bounds tablets", poolName);
                  }
                }
              } catch (TException e1) {
                LOG.error("Error in OOB check getting tablets for table {} from server {}", tid, e.getKey().host(), e);
              }
            }
          }
        }
      } finally {
        this.lastOOBCheck = System.currentTimeMillis();
      }
    }

    if (migrations != null && migrations.size() > 0) {
      LOG.warn("Not balancing tables due to {} outstanding migrations", migrations.size());
      return minBalanceTime;
    }

    for (String s : t.tableIdMap().values()) {
      String tableName = tableIdToTableName.get(s);
      String regexTableName = getPoolNameForTable(tableName);
      SortedMap<TServerInstance,TabletServerStatus> currentView = currentGrouped.get(regexTableName);
      if (null == currentView) {
        LOG.warn("Skipping balance for table {} as no tablet servers are online, will recheck for online tservers at {} ms intervals", tableName,
            this.poolRecheckMillis);
        continue;
      }
      ArrayList<TabletMigration> newMigrations = new ArrayList<TabletMigration>();
      long tableBalanceTime = getBalancerForTable(s).balance(currentView, migrations, newMigrations);
      if (tableBalanceTime < minBalanceTime) {
        minBalanceTime = tableBalanceTime;
      }
      migrationsOut.addAll(newMigrations);
    }

    return minBalanceTime;
  }

}