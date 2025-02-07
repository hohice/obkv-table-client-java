/*-
 * #%L
 * OBKV Table Client Framework
 * %%
 * Copyright (C) 2021 OceanBase
 * %%
 * OBKV Table Client Framework is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 * #L%
 */

package com.alipay.oceanbase.rpc.location;

import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alipay.oceanbase.rpc.constant.Constants;
import com.alipay.oceanbase.rpc.exception.*;
import com.alipay.oceanbase.rpc.location.model.*;
import com.alipay.oceanbase.rpc.location.model.partition.*;
import com.alipay.oceanbase.rpc.protocol.payload.ResultCodes;
import com.alipay.oceanbase.rpc.protocol.payload.impl.ObCollationType;
import com.alipay.oceanbase.rpc.protocol.payload.impl.ObColumn;
import com.alipay.oceanbase.rpc.protocol.payload.impl.ObObjType;
import com.alipay.oceanbase.rpc.protocol.payload.impl.column.ObGeneratedColumn;
import com.alipay.oceanbase.rpc.protocol.payload.impl.column.ObSimpleColumn;
import com.alipay.oceanbase.rpc.protocol.payload.impl.parser.ObGeneratedColumnExpressParser;
import com.alipay.oceanbase.rpc.util.TableClientLoggerFactory;
import org.slf4j.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.text.MessageFormat;
import java.util.*;

import static com.alipay.oceanbase.rpc.location.model.partition.ObPartitionKey.MAX_PARTITION_ELEMENT;
import static com.alipay.oceanbase.rpc.location.model.partition.ObPartitionKey.MIN_PARTITION_ELEMENT;
import static com.alipay.oceanbase.rpc.util.RandomUtil.getRandomNum;
import static com.alipay.oceanbase.rpc.util.TableClientLoggerFactory.LCD;
import static java.lang.String.format;

public class LocationUtil {

    private static final Logger logger                        = TableClientLoggerFactory
                                                                  .getLogger(LocationUtil.class);
    static {
        ParserConfig.getGlobalInstance().setSafeMode(true);
    }

    @Deprecated
    @SuppressWarnings("unused")
    private static final String PROXY_PLAIN_SCHEMA_SQL_FORMAT = "SELECT /*+READ_CONSISTENCY(WEAK)*/ partition_id, svr_ip, sql_port, table_id, role, part_num, replica_num, schema_version, spare1 "
                                                                + "FROM oceanbase.__all_virtual_proxy_schema "
                                                                + "WHERE tenant_name = ? AND database_name = ?  AND table_name = ? AND partition_id in ({0}) AND sql_port > 0 "
                                                                + "ORDER BY role ASC LIMIT ?";

    private static final String PROXY_PART_INFO_SQL           = "SELECT /*+READ_CONSISTENCY(WEAK)*/ part_level, part_num, part_type, part_space, part_expr, "
                                                                + "part_range_type, part_interval_bin, interval_start_bin, "
                                                                + "sub_part_num, sub_part_type, sub_part_space, "
                                                                + "sub_part_range_type, def_sub_part_interval_bin, def_sub_interval_start_bin, sub_part_expr, "
                                                                + "part_key_name, part_key_type, part_key_idx, part_key_extra, spare1 "
                                                                + "FROM oceanbase.__all_virtual_proxy_partition_info "
                                                                + "WHERE table_id = ? group by part_key_name order by part_key_name LIMIT ?;";
    @Deprecated
    @SuppressWarnings("unused")
    private static final String PROXY_TENANT_SCHEMA_SQL       = "SELECT /*+READ_CONSISTENCY(WEAK)*/ svr_ip, sql_port, table_id, role, part_num, replica_num, spare1 "
                                                                + "FROM oceanbase.__all_virtual_proxy_schema "
                                                                + "WHERE tenant_name = ? AND database_name = ?  AND table_name = ? AND sql_port > 0 "
                                                                + "ORDER BY partition_id ASC, role ASC LIMIT ?";

    private static final String PROXY_DUMMY_LOCATION_SQL      = "SELECT /*+READ_CONSISTENCY(WEAK)*/ A.partition_id as partition_id, A.svr_ip as svr_ip, A.sql_port as sql_port, "
                                                                + "A.table_id as table_id, A.role as role, A.replica_num as replica_num, A.part_num as part_num, B.svr_port as svr_port, B.status as status, B.stop_time as stop_time "
                                                                + ", A.spare1 as replica_type "
                                                                + "FROM oceanbase.__all_virtual_proxy_schema A inner join oceanbase.__all_server B on A.svr_ip = B.svr_ip and A.sql_port = B.inner_port "
                                                                + "WHERE tenant_name = ? and database_name=? and table_name = ?";

    private static final String PROXY_LOCATION_SQL            = "SELECT /*+READ_CONSISTENCY(WEAK)*/ A.partition_id as partition_id, A.svr_ip as svr_ip, A.sql_port as sql_port, "
                                                                + "A.table_id as table_id, A.role as role, A.replica_num as replica_num, A.part_num as part_num, B.svr_port as svr_port, B.status as status, B.stop_time as stop_time "
                                                                + ", A.spare1 as replica_type "
                                                                + "FROM oceanbase.__all_virtual_proxy_schema A inner join oceanbase.__all_server B on A.svr_ip = B.svr_ip and A.sql_port = B.inner_port "
                                                                + "WHERE tenant_name = ? and database_name=? and table_name = ? and partition_id = 0";

    private static final String PROXY_LOCATION_SQL_PARTITION  = "SELECT /*+READ_CONSISTENCY(WEAK)*/ A.partition_id as partition_id, A.svr_ip as svr_ip, A.sql_port as sql_port, "
                                                                + "A.table_id as table_id, A.role as role, A.replica_num as replica_num, A.part_num as part_num, B.svr_port as svr_port, B.status as status, B.stop_time as stop_time "
                                                                + ", A.spare1 as replica_type "
                                                                + "FROM oceanbase.__all_virtual_proxy_schema A inner join oceanbase.__all_server B on A.svr_ip = B.svr_ip and A.sql_port = B.inner_port "
                                                                + "WHERE tenant_name = ? and database_name=? and table_name = ? and partition_id in ({0})";

    private static final String PROXY_FIRST_PARTITION_SQL     = "SELECT /*+READ_CONSISTENCY(WEAK)*/ part_id, part_name, high_bound_val "
                                                                + "FROM oceanbase.__all_virtual_proxy_partition "
                                                                + "WHERE table_id = ? LIMIT ?;";

    private static final String PROXY_SUB_PARTITION_SQL       = "SELECT /*+READ_CONSISTENCY(WEAK)*/ sub_part_id, part_name, high_bound_val "
                                                                + "FROM oceanbase.__all_virtual_proxy_sub_partition "
                                                                + "WHERE table_id = ? and part_id = ? LIMIT ?;";

    private static final String PROXY_SERVER_STATUS_INFO      = "SELECT ss.svr_ip, ss.zone, zs.region, zs.spare4 as idc "
                                                                + "FROM oceanbase.__all_virtual_proxy_server_stat ss, oceanbase.__all_virtual_zone_stat zs "
                                                                + "WHERE zs.zone = ss.zone ;";

    private static final String home                          = System.getProperty("user.home",
                                                                  "/home/admin");

    private static final int    TEMPLATE_PART_ID              = -1;

    private abstract static class TableEntryRefreshWithPriorityCallback<T> {
        abstract T execute(ObServerAddr obServerAddr) throws ObTableEntryRefreshException;
    }

    private abstract static class TableEntryRefreshCallback<T> {
        abstract T execute(Connection connection) throws ObTableEntryRefreshException;
    }

    private static ObServerAddr randomObServers(List<ObServerAddr> obServerAddrs) {
        return obServerAddrs.get(getRandomNum(0, obServerAddrs.size()));
    }

    private static TableEntry callTableEntryRefreshWithPriority(ServerRoster serverRoster,
                                                                long priorityTimeout,
                                                                long cachingTimeout,
                                                                TableEntryRefreshWithPriorityCallback<TableEntry> callable)
                                                                                                                           throws ObTableEntryRefreshException {
        ObServerAddr addr = serverRoster.getServer(priorityTimeout, cachingTimeout);
        try {
            TableEntry tableEntry = callable.execute(addr);
            serverRoster.resetPriority(addr);
            return tableEntry;
        } catch (ObTableEntryRefreshException e) {
            serverRoster.downgradePriority(addr);
            throw e;
        }
    }

    /*
     * Get Server LDC info from OB system table.
     */
    public static List<ObServerLdcItem> getServerLdc(ServerRoster serverRoster,
                                                     final long connectTimeout,
                                                     final long socketTimeout,
                                                     final long priorityTimeout,
                                                     final long cachingTimeout,
                                                     final ObUserAuth sysUA)
                                                                            throws ObTableEntryRefreshException {
        ObServerAddr addr = serverRoster.getServer(priorityTimeout, cachingTimeout);
        try {
            List<ObServerLdcItem> ss = callServerLdcRefresh(addr, connectTimeout, socketTimeout,
                sysUA);
            serverRoster.resetPriority(addr);
            return ss;
        } catch (ObTableEntryRefreshException e) {
            serverRoster.downgradePriority(addr);
            throw e;
        }
    }

    /*
     * Format ob server url with the tenant server.
     *
     * @param obServerAddr
     * @param connectTimeout
     * @param socketTimeout
     * @return
     */
    private static String formatObServerUrl(ObServerAddr obServerAddr, long connectTimeout,
                                            long socketTimeout) {
        return format(
            "jdbc:mysql://%s/oceanbase?useUnicode=true&characterEncoding=utf-8&connectTimeout=%d&socketTimeout=%d",
            obServerAddr.getIp() + ":" + obServerAddr.getSqlPort(), connectTimeout, socketTimeout);
    }

    /*
     * Establish db connection to the given database URL, with proxy user/password.
     *
     * @param url
     * @return
     * @throws ObTableEntryRefreshException
     */
    private static Connection getMetaRefreshConnection(String url, ObUserAuth sysUA)
                                                                                    throws ObTableEntryRefreshException {
        try {
            Class.forName("com.mysql.jdbc.Driver");
            return DriverManager.getConnection(url, sysUA.getUserName(), sysUA.getPassword());
        } catch (ClassNotFoundException e) {
            logger.error(LCD.convert("01-00006"), e.getMessage(), e);
            throw new ObTableEntryRefreshException(format(
                "fail to find com.mysql.jdbc.Driver, errMsg=%s", e.getMessage()), e);
        } catch (Exception e) {
            logger.error(LCD.convert("01-00005"), e.getMessage(), e);
            throw new ObTableEntryRefreshException("fail to decode proxyro password", e);
        }

    }

    /*
     * Refresh server LDC info.
     *
     * @param obServerAddr
     * @param connectTimeout
     * @param socketTimeout
     * @return List<ObServerLdcItem>
     * @throws ObTableEntryRefreshException
     */
    private static List<ObServerLdcItem> callServerLdcRefresh(ObServerAddr obServerAddr,
                                                              long connectTimeout,
                                                              long socketTimeout, ObUserAuth sysUA)
                                                                                                   throws ObTableEntryRefreshException {
        String url = formatObServerUrl(obServerAddr, connectTimeout, socketTimeout);
        List<ObServerLdcItem> ss = new ArrayList<ObServerLdcItem>();
        Connection connection = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            connection = getMetaRefreshConnection(url, sysUA);
            ps = connection.prepareStatement(PROXY_SERVER_STATUS_INFO);
            rs = ps.executeQuery();
            while (rs.next()) {
                String ip = rs.getString("svr_ip");
                String zone = rs.getString("zone");
                String idc = rs.getString("idc");
                String region = rs.getString("region");
                ss.add(new ObServerLdcItem(ip, zone, idc, region));
            }
        } catch (Exception e) {
            logger.error(LCD.convert("01-00027"), url, e);
            throw new ObTableEntryRefreshException(format(
                "fail to refresh server LDC from remote url=%s", url), e);
        } finally {
            try {
                if (null != rs) {
                    rs.close();
                }
                if (null != ps) {
                    ps.close();
                }
                if (null != connection) {
                    connection.close();
                }
            } catch (SQLException e) {
                // ignore
            }
        }
        return ss;

    }

    private static TableEntry callTableEntryRefresh(ObServerAddr obServerAddr, TableEntryKey key,
                                                    long connectTimeout, long socketTimeout,
                                                    ObUserAuth sysUA,
                                                    TableEntryRefreshCallback<TableEntry> callback)
                                                                                                   throws ObTableEntryRefreshException {
        String url = formatObServerUrl(obServerAddr, connectTimeout, socketTimeout);
        Connection connection = null;
        TableEntry entry;
        try {
            connection = getMetaRefreshConnection(url, sysUA);
            entry = callback.execute(connection);
        } catch (ObTableNotExistException e) {
            // avoid to refresh meta for ObTableNotExistException
            throw e;
        } catch (Exception e) {
            logger.error(LCD.convert("01-00007"), url, key, e);
            throw new ObTableEntryRefreshException(format(
                "fail to refresh table entry from remote url=%s, key=%s", url, key), e);
        } finally {
            try {
                if (null != connection) {
                    connection.close();
                }
            } catch (SQLException e) {
                // ignore
            }
        }

        if (entry != null && entry.isValid()) {
            entry.setRefreshTimeMills(System.currentTimeMillis());
            return entry;
        } else {
            logger.error(LCD.convert("01-00008"), obServerAddr, key, entry);
            throw new ObTableEntryRefreshException("table entry is invalid, addr = " + obServerAddr
                                                   + " key =" + key + " entry =" + entry);
        }

    }

    /*
     * Load table entry with priority.
     */
    public static TableEntry loadTableEntryWithPriority(final ServerRoster serverRoster,
                                                        final TableEntryKey key,
                                                        final long connectTimeout,
                                                        final long socketTimeout,
                                                        final long priorityTimeout,
                                                        final long cachingTimeout,
                                                        final ObUserAuth sysUA)
                                                                               throws ObTableEntryRefreshException {
        return callTableEntryRefreshWithPriority(serverRoster, priorityTimeout, cachingTimeout,
            new TableEntryRefreshWithPriorityCallback<TableEntry>() {
                @Override
                TableEntry execute(ObServerAddr obServerAddr) throws ObTableEntryRefreshException {
                    return callTableEntryRefresh(obServerAddr, key, connectTimeout, socketTimeout,
                        sysUA, new TableEntryRefreshCallback<TableEntry>() {
                            @Override
                            TableEntry execute(Connection connection)
                                                                     throws ObTableEntryRefreshException {
                                return getTableEntryFromRemote(connection, key);
                            }
                        });
                }
            });
    }

    /*
     * Load table entry location with priority.
     */
    public static TableEntry loadTableEntryLocationWithPriority(final ServerRoster serverRoster,
                                                                final TableEntryKey key,
                                                                final TableEntry tableEntry,
                                                                final long connectTimeout,
                                                                final long socketTimeout,
                                                                final long priorityTimeout,
                                                                final long cachingTimeout,
                                                                final ObUserAuth sysUA)
                                                                                       throws ObTableEntryRefreshException {

        return callTableEntryRefreshWithPriority(serverRoster, priorityTimeout, cachingTimeout,
            new TableEntryRefreshWithPriorityCallback<TableEntry>() {
                @Override
                TableEntry execute(ObServerAddr obServerAddr) throws ObTableEntryRefreshException {
                    return callTableEntryRefresh(obServerAddr, key, connectTimeout, socketTimeout,
                        sysUA, new TableEntryRefreshCallback<TableEntry>() {
                            @Override
                            TableEntry execute(Connection connection)
                                                                     throws ObTablePartitionLocationRefreshException {
                                return getTableEntryLocationFromRemote(connection, key, tableEntry);
                            }
                        });
                }
            });
    }

    /*
     * Load table entry randomly.
     */
    public static TableEntry loadTableEntryRandomly(final List<ObServerAddr> rsList,//
                                                    final TableEntryKey key, //
                                                    final long connectTimeout,//
                                                    final long socketTimeout, final ObUserAuth sysUA)
                                                                                                     throws ObTableEntryRefreshException {
        return callTableEntryRefresh(randomObServers(rsList), key, connectTimeout, socketTimeout,
            sysUA, new TableEntryRefreshCallback<TableEntry>() {
                @Override
                TableEntry execute(Connection connection) throws ObTableEntryRefreshException {
                    return getTableEntryFromRemote(connection, key);
                }
            });
    }

    private static TableEntry getTableEntryFromRemote(Connection connection, TableEntryKey key)
                                                                                               throws ObTableEntryRefreshException {
        PreparedStatement ps = null;
        ResultSet rs = null;
        TableEntry tableEntry;
        try {
            if (key.getTableName().equals(Constants.ALL_DUMMY_TABLE)) {
                ps = connection.prepareStatement(PROXY_DUMMY_LOCATION_SQL);
                ps.setString(1, key.getTenantName());
                ps.setString(2, key.getDatabaseName());
                ps.setString(3, key.getTableName());
            } else {
                ps = connection.prepareStatement(PROXY_LOCATION_SQL);
                ps.setString(1, key.getTenantName());
                ps.setString(2, key.getDatabaseName());
                ps.setString(3, key.getTableName());
            }
            rs = ps.executeQuery();
            tableEntry = getTableEntryFromResultSet(key, rs);
            if (null != tableEntry) {
                tableEntry.setTableEntryKey(key);
                getTableEntryLocationFromRemote(connection, key, tableEntry);
                // TODO: check capacity flag later
                if (tableEntry.isPartitionTable()) {
                    // fetch partition info
                    fetchPartitionInfo(connection, tableEntry);
                    if (null != tableEntry.getPartitionInfo()) {
                        // fetch first range part
                        if (null != tableEntry.getPartitionInfo().getFirstPartDesc()) {
                            ObPartFuncType obPartFuncType = tableEntry.getPartitionInfo()
                                .getFirstPartDesc().getPartFuncType();
                            if (obPartFuncType.isRangePart() || obPartFuncType.isListPart()) {
                                fetchFirstPart(connection, tableEntry, obPartFuncType);
                            }
                        }
                        // fetch sub range part
                        if (null != tableEntry.getPartitionInfo().getSubPartDesc()) {
                            ObPartFuncType subPartFuncType = tableEntry.getPartitionInfo()
                                .getSubPartDesc().getPartFuncType();
                            if (subPartFuncType.isRangePart() || subPartFuncType.isListPart()) {
                                fetchSubPart(connection, tableEntry, subPartFuncType);
                            }
                        }
                        // build partition name-id map
                        tableEntry.getPartitionInfo().setPartNameIdMap(
                            buildPartNameIdMap(tableEntry.getPartitionInfo()));
                    }
                }

                if (logger.isInfoEnabled()) {
                    logger.info("get table entry from remote, entry={}", tableEntry);
                }
            }
        } catch (ObTableNotExistException e) {
            // avoid to refresh meta for ObTableNotExistException
            throw e;
        } catch (Exception e) {
            logger.error(LCD.convert("01-00009"), key, e);
            throw new ObTableEntryRefreshException(format(
                "fail to get table entry from remote, key=%s", key), e);
        } finally {
            try {
                if (null != rs) {
                    rs.close();
                }
                if (null != ps) {
                    ps.close();
                }
            } catch (SQLException e) {
                // ignore
            }
        }
        return tableEntry;
    }

    /*
     * Get table entry location from remote.
     */
    public static TableEntry getTableEntryLocationFromRemote(Connection connection,
                                                             TableEntryKey key,
                                                             TableEntry tableEntry)
                                                                                   throws ObTablePartitionLocationRefreshException {

        PreparedStatement ps = null;
        ResultSet rs = null;
        long partitionNum = tableEntry.getPartitionNum();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < partitionNum; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(i);
        }
        ObPartitionEntry partitionEntry;
        String sql = MessageFormat.format(PROXY_LOCATION_SQL_PARTITION, sb.toString());
        try {
            ps = connection.prepareStatement(sql);
            ps.setString(1, key.getTenantName());
            ps.setString(2, key.getDatabaseName());
            ps.setString(3, key.getTableName());

            rs = ps.executeQuery();
            partitionEntry = getPartitionLocationFromResultSet(tableEntry, rs);
            tableEntry.setPartitionEntry(partitionEntry);
            tableEntry.setRefreshTimeMills(System.currentTimeMillis());
        } catch (Exception e) {
            logger.error(LCD.convert("01-00010"), key, partitionNum, tableEntry, e);
            throw new ObTablePartitionLocationRefreshException(
                format(
                    "fail to get partition location entry from remote entryKey = %s partNum = %d tableEntry =%s",
                    key, partitionNum, tableEntry), e);
        } finally {
            try {
                if (null != rs) {
                    rs.close();
                }
                if (null != ps) {
                    ps.close();
                }
            } catch (SQLException e) {
                // ignore
            }
        }
        return tableEntry;
    }

    private static void fetchFirstPart(Connection connection, TableEntry tableEntry,
                                       ObPartFuncType obPartFuncType)
                                                                     throws ObTablePartitionInfoRefreshException {
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = connection.prepareStatement(PROXY_FIRST_PARTITION_SQL);
            ps.setLong(1, tableEntry.getTableId());
            ps.setInt(2, Integer.MAX_VALUE);

            rs = ps.executeQuery();
            if (obPartFuncType.isRangePart()) {
                List<ObComparableKV<ObPartitionKey, Long>> bounds = parseFirstPartRange(rs,
                    tableEntry);
                ((ObRangePartDesc) tableEntry.getPartitionInfo().getFirstPartDesc())
                    .setBounds(bounds);

                if (logger.isInfoEnabled()) {
                    logger.info(format("get first ranges from remote, bounds=%s for tableEntry=%s",
                        bounds, tableEntry));
                }

            } else if (obPartFuncType.isListPart()) {
                Map<ObPartitionKey, Long> sets = parseFirstPartSets(rs, tableEntry);
                ((ObListPartDesc) tableEntry.getPartitionInfo().getFirstPartDesc()).setSets(sets);
                if (logger.isInfoEnabled()) {
                    logger.info(format(
                        "get first list sets from remote, sets=%s for tableEntry=%s", sets,
                        tableEntry));
                }
            }
        } catch (Exception e) {

            logger.error(LCD.convert("01-00011"), tableEntry, obPartFuncType, e);

            throw new ObTablePartitionInfoRefreshException(format(
                "fail to get first part from remote, tableEntry=%s partFuncType=%s", tableEntry,
                obPartFuncType), e);
        } finally {
            try {
                if (null != rs) {
                    rs.close();
                }
                if (null != ps) {
                    ps.close();
                }
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    private static void fetchSubPart(Connection connection, TableEntry tableEntry,
                                     ObPartFuncType subPartFuncType)
                                                                    throws ObTablePartitionInfoRefreshException {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = connection.prepareStatement(PROXY_SUB_PARTITION_SQL);
            pstmt.setLong(1, tableEntry.getTableId());
            pstmt.setLong(2, TEMPLATE_PART_ID);
            pstmt.setInt(3, Integer.MAX_VALUE);

            rs = pstmt.executeQuery();
            if (subPartFuncType.isRangePart()) {
                List<ObComparableKV<ObPartitionKey, Long>> bounds = parseSubPartRange(rs,
                    tableEntry);
                ((ObRangePartDesc) tableEntry.getPartitionInfo().getSubPartDesc())
                    .setBounds(bounds);
                if (logger.isInfoEnabled()) {
                    logger.info(format("success to get sub ranges from remote, bounds=%s", bounds));
                }
            } else if (subPartFuncType.isListPart()) {
                Map<ObPartitionKey, Long> sets = parseSubPartSets(rs, tableEntry);
                ((ObListPartDesc) tableEntry.getPartitionInfo().getSubPartDesc()).setSets(sets);
                if (logger.isInfoEnabled()) {
                    logger.info(format("success to get sub list sets from remote, sets=%s", sets));
                }
            }
        } catch (Exception e) {
            logger.error(LCD.convert("01-00012"), tableEntry, subPartFuncType, e);
            throw new ObTablePartitionInfoRefreshException(format(
                "fail to get sub part from remote, tableEntry=%s partFuncType=%s", tableEntry,
                subPartFuncType), e);
        } finally {
            try {
                if (null != rs) {
                    rs.close();
                }
                if (null != pstmt) {
                    pstmt.close();
                }
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    private static TableEntry getTableEntryFromResultSet(TableEntryKey key, ResultSet rs)
                                                                                         throws SQLException,
                                                                                         ObTableEntryRefreshException {
        TableEntry entry = new TableEntry();
        Long replicaNum = null;
        Long partitionNum = null;
        Long tableId = null;
        List<ReplicaLocation> replicaLocations = new ArrayList<ReplicaLocation>(3);
        while (rs.next()) {
            ReplicaLocation replica = buildReplicaLocation(rs);
            tableId = rs.getLong("table_id");
            replicaNum = rs.getLong("replica_num");
            partitionNum = rs.getLong("part_num");
            if (!replica.isValid()) {
                logger
                    .warn(format("replica is invalid, continue, replica=%s, key=%s", replica, key));
            } else if (replicaLocations.contains(replica)) {
                logger.warn(format(
                    "replica is repeated, continue, replica=%s, key=%s, replicas=%s", replica, key,
                    replicaLocations));
            } else {
                replicaLocations.add(replica);
            }
        }
        TableLocation tableLocation = new TableLocation();
        tableLocation.setReplicaLocations(replicaLocations);
        if (!replicaLocations.isEmpty()) {
            entry.setTableId(tableId);
            entry.setTableLocation(tableLocation);
            entry.setPartitionNum(partitionNum);
            entry.setReplicaNum(replicaNum);
        } else {
            throw new ObTableNotExistException("table not exist: " + key.getTableName(),
                ResultCodes.OB_ERR_UNKNOWN_TABLE.errorCode);
        }

        return entry;
    }

    private static ObPartitionEntry getPartitionLocationFromResultSet(TableEntry tableEntry,
                                                                      ResultSet rs)
                                                                                   throws SQLException,
                                                                                   ObTablePartitionLocationRefreshException {
        Map<Long, ObPartitionLocation> partitionLocation = new HashMap<Long, ObPartitionLocation>();
        while (rs.next()) {
            ReplicaLocation replica = buildReplicaLocation(rs);
            long partitionId = rs.getLong("partition_id");
            if (!replica.isValid()) {
                logger.warn(format(
                    "replica is invalid, continue, replica=%s, partitionId=%d, tableId=%d",
                    replica, partitionId, tableEntry.getTableId()));
                continue;
            }
            ObPartitionLocation location = partitionLocation.get(partitionId);

            if (location == null) {
                location = new ObPartitionLocation();
                partitionLocation.put(partitionId, location);
            }
            location.addReplicaLocation(replica);
        }
        ObPartitionEntry partitionEntry = new ObPartitionEntry();
        partitionEntry.setPartitionLocation(partitionLocation);

        for (long i = 0; i < tableEntry.getPartitionNum(); i++) {
            ObPartitionLocation location = partitionEntry.getPartitionLocationWithPartId(i);
            if (location == null) {
                logger.error(LCD.convert("01-00013"), i, partitionEntry, tableEntry);
                throw new ObTablePartitionNotExistException(format(
                    "partition num=%d is not exist partitionEntry=%s original tableEntry=%s", i,
                    partitionEntry, tableEntry));
            }
            if (location.getLeader() == null) {
                logger.error(LCD.convert("01-00028"), i, partitionEntry, tableEntry);
                throw new ObTablePartitionNoMasterException(format(
                    "partition num=%d has no leader partitionEntry=%s original tableEntry=%s", i,
                    partitionEntry, tableEntry));
            }
        }

        return partitionEntry;
    }

    /*
     * Get ReplicaLocation from the result row.
     *
     * @param rs
     * @return
     * @throws SQLException
     */
    private static ReplicaLocation buildReplicaLocation(ResultSet rs) throws SQLException {
        String ip = rs.getString("svr_ip");
        int port = rs.getInt("sql_port");
        int svrPort = rs.getInt("svr_port");
        ObServerRole role = ObServerRole.getRole(rs.getInt("role"));
        String status = rs.getString("status");
        long stopTime = rs.getLong("stop_time");
        ObReplicaType replicaType = ObReplicaType.getReplicaType(rs.getInt("replica_type"));

        ReplicaLocation replica = new ReplicaLocation();
        ObServerAddr obServerAddr = new ObServerAddr();
        obServerAddr.setAddress(ip);
        obServerAddr.setSqlPort(port);
        obServerAddr.setSvrPort(svrPort);

        ObServerInfo obServerInfo = new ObServerInfo();
        obServerInfo.setStatus(status);
        obServerInfo.setStopTime(stopTime);

        replica.setAddr(obServerAddr);
        replica.setInfo(obServerInfo);
        replica.setRole(role);
        replica.setReplicaType(replicaType);
        return replica;
    }

    private static void fetchPartitionInfo(Connection connection, TableEntry tableEntry)
                                                                                        throws ObTablePartitionInfoRefreshException {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        ObPartitionInfo info = null;
        try {
            pstmt = connection.prepareStatement(PROXY_PART_INFO_SQL);
            pstmt.setLong(1, tableEntry.getTableId());
            pstmt.setLong(2, Long.MAX_VALUE);

            rs = pstmt.executeQuery();
            info = parsePartitionInfo(rs);

            if (logger.isInfoEnabled()) {
                logger.info("get part info from remote " + info);
            }
            tableEntry.setPartitionInfo(info);
        } catch (Exception e) {
            logger.error(LCD.convert("01-00014"), tableEntry);
            throw new ObTablePartitionInfoRefreshException(format(
                "fail to get part info from remote, tableEntry=%s", tableEntry), e);
        } finally {
            try {
                if (null != rs) {
                    rs.close();
                }
                if (null != pstmt) {
                    pstmt.close();
                }
            } catch (SQLException e) {
                // ignore
            }
        }

    }

    private static ObPartitionInfo parsePartitionInfo(ResultSet rs)
                                                                   throws IllegalArgumentException,
                                                                   GenerateColumnParseException,
                                                                   SQLException {
        ObPartitionInfo info = new ObPartitionInfo();
        boolean isFirstRow = true;
        while (rs.next()) {
            // get part info for the first loop
            if (isFirstRow) {
                isFirstRow = false;
                // get part level
                info.setLevel(ObPartitionLevel.valueOf(rs.getLong("part_level")));

                // get first part
                if (info.getLevel().getIndex() >= ObPartitionLevel.LEVEL_ONE.getIndex()) {
                    ObPartDesc partDesc = buildPartDesc(ObPartitionLevel.LEVEL_ONE, rs);
                    if (partDesc == null) {
                        logger.warn("fail to build first part");
                    } else {
                        info.setFirstPartDesc(partDesc);
                    }
                }

                // get sub part
                if (info.getLevel().getIndex() == ObPartitionLevel.LEVEL_TWO.getIndex()) {
                    ObPartDesc partDesc = buildPartDesc(ObPartitionLevel.LEVEL_TWO, rs);
                    if (partDesc == null) {
                        logger.warn("fail to build sub part");
                    } else {
                        info.setSubPartDesc(partDesc);
                    }
                }
            }

            // get part key for each loop
            String partKeyExtra = rs.getString("part_key_extra");
            partKeyExtra = partKeyExtra.replace("`", ""); // '`' is not supported by druid
            ObColumn column;
            if (!partKeyExtra.isEmpty()) {
                column = new ObGeneratedColumn(
                    rs.getString("part_key_name"),//
                    rs.getInt("part_key_idx"),//
                    ObObjType.valueOf(rs.getInt("part_key_type")),//
                    ObCollationType.valueOf(rs.getInt("spare1")),
                    new ObGeneratedColumnExpressParser(getPlainString(partKeyExtra)).parse());
            } else {
                column = new ObSimpleColumn(rs.getString("part_key_name"),//
                    rs.getInt("part_key_idx"),//
                    ObObjType.valueOf(rs.getInt("part_key_type")),//
                    ObCollationType.valueOf(rs.getInt("spare1")));
            }

            info.addColumn(column);
        }

        // get list partition column types here
        List<ObColumn> orderedPartedColumns = null;
        if (null != info.getFirstPartDesc()) {
            if (info.getFirstPartDesc().getPartFuncType().isListPart()
                || info.getFirstPartDesc().getPartFuncType().isRangePart()) {
                orderedPartedColumns = getOrderedPartColumns(info.getPartColumns(),
                    info.getFirstPartDesc());
            }
        }
        if (null != info.getSubPartDesc()) {
            if (info.getSubPartDesc().getPartFuncType().isListPart()
                || info.getFirstPartDesc().getPartFuncType().isRangePart()) {
                orderedPartedColumns = getOrderedPartColumns(info.getPartColumns(),
                    info.getSubPartDesc());
            }
        }

        // set the property of first part and sub part
        setPartDescProperty(info.getFirstPartDesc(), info.getPartColumns(), orderedPartedColumns);
        setPartDescProperty(info.getSubPartDesc(), info.getPartColumns(), orderedPartedColumns);

        return info;
    }

    private static ObPartDesc buildPartDesc(ObPartitionLevel level, ResultSet rs)
                                                                                 throws SQLException {
        ObPartDesc partDesc = null;
        String partLevelPrefix = (level == ObPartitionLevel.LEVEL_TWO ? "sub_" : "");
        ObPartFuncType partType = ObPartFuncType.getObPartFuncType(rs.getLong(partLevelPrefix
                                                                              + "part_type"));
        String partExpr = rs.getString(partLevelPrefix + "part_expr");
        partExpr = partExpr.replace("`", ""); // '`' is not supported by druid

        if (partType.isRangePart()) {
            ObRangePartDesc rangeDesc = new ObRangePartDesc();
            rangeDesc.setPartFuncType(partType);
            rangeDesc.setPartExpr(partExpr);
            ArrayList<ObObjType> types = new ArrayList<ObObjType>(1);
            String objTypesStr = rs.getString(partLevelPrefix + "part_range_type");
            for (String typeStr : objTypesStr.split(",")) {
                types.add(ObObjType.valueOf(Integer.valueOf(typeStr)));
            }
            rangeDesc.setOrderedCompareColumnTypes(types);
            partDesc = rangeDesc;
        } else if (partType.isHashPart()) {
            ObHashPartDesc hashDesc = new ObHashPartDesc();
            hashDesc.setPartExpr(partExpr);
            hashDesc.setPartFuncType(partType);
            hashDesc.setPartNum(rs.getInt(partLevelPrefix + "part_num"));
            hashDesc.setPartSpace(rs.getInt(partLevelPrefix + "part_space"));
            Map<String, Long> partNameIdMap = buildDefaultPartNameIdMap(hashDesc.getPartNum());
            hashDesc.setPartNameIdMap(partNameIdMap);
            partDesc = hashDesc;
        } else if (partType.isKeyPart()) {
            ObKeyPartDesc keyPartDesc = new ObKeyPartDesc();
            keyPartDesc.setPartFuncType(partType);
            keyPartDesc.setPartExpr(partExpr);
            keyPartDesc.setPartNum(rs.getInt(partLevelPrefix + "part_num"));
            keyPartDesc.setPartSpace(rs.getInt(partLevelPrefix + "part_space"));
            Map<String, Long> partNameIdMap = buildDefaultPartNameIdMap(keyPartDesc.getPartNum());
            keyPartDesc.setPartNameIdMap(partNameIdMap);
            partDesc = keyPartDesc;
        } else {
            logger.error(LCD.convert("01-00015"), partType);
            throw new IllegalArgumentException(format("not supported part type, type = %s",
                partType));
        }
        return partDesc;
    }

    private static List<ObColumn> getOrderedPartColumns(List<ObColumn> partitionKeyColumns,
                                                        ObPartDesc partDesc) {
        List<ObColumn> columns = new ArrayList<ObColumn>();
        for (String partColName : partDesc.getOrderedPartColumnNames()) {
            for (ObColumn keyColumn : partitionKeyColumns) {
                if (partColName.equalsIgnoreCase(keyColumn.getColumnName())) {
                    columns.add(keyColumn);
                }
            }
        }
        return columns;
    }

    private static void setPartDescProperty(ObPartDesc partDesc, List<ObColumn> partColumns,
                                            List<ObColumn> listPartColumns)
                                                                           throws ObTablePartitionInfoRefreshException {
        ObPartFuncType obPartFuncType = null;
        if (null != partDesc) {
            partDesc.setPartColumns(partColumns);
            obPartFuncType = partDesc.getPartFuncType();
            if (obPartFuncType.isKeyPart()) {
                if (partColumns == null || partColumns.size() == 0) {
                    throw new ObTablePartitionInfoRefreshException(
                        "key part desc need part ref columns but found " + partColumns);
                }
            } else if (obPartFuncType.isListPart()) {
                ((ObListPartDesc) partDesc).setOrderCompareColumns(listPartColumns);
            } else if (obPartFuncType.isRangePart()) {
                ((ObRangePartDesc) partDesc).setOrderedCompareColumns(listPartColumns);
            }
        }
    }

    public static Map<String, Long> buildDefaultPartNameIdMap(int partNum) {
        // the default partition name is 'p0,p1...'
        Map<String, Long> partNameIdMap = new HashMap<String, Long>();
        for (int i = 0; i < partNum; i++) {
            partNameIdMap.put("p" + i, (long) i);
        }
        return partNameIdMap;
    }

    private static Map<String, Long> buildPartNameIdMap(ObPartitionInfo partitionInfo) {
        Map<String, Long> partNameIdMap1 = partitionInfo.getFirstPartDesc().getPartNameIdMap();
        Map<String, Long> partNameIdMap2 = Collections.EMPTY_MAP;
        Map<String, Long> partNameIdMap = new HashMap<String, Long>();
        for (String partName1 : partNameIdMap1.keySet()) {
            Long partId1 = partNameIdMap1.get(partName1);
            if (null != partitionInfo.getSubPartDesc()) {
                partNameIdMap2 = partitionInfo.getSubPartDesc().getPartNameIdMap();
                for (String partName2 : partNameIdMap2.keySet()) {
                    String comPartName = partName1 + "s" + partName2;
                    Long partId2 = partNameIdMap2.get(partName2);
                    Long partId = ObPartIdCalculator.generatePartId(partId1, partId2);
                    partNameIdMap.put(comPartName, partId);
                }
            } else {
                partNameIdMap.put(partName1, partId1);
            }
        }

        return partNameIdMap;
    }

    private static List<ObComparableKV<ObPartitionKey, Long>> parseFirstPartRange(ResultSet rs,
                                                                                  TableEntry tableEntry)
                                                                                                        throws SQLException,
                                                                                                        IllegalArgumentException,
                                                                                                        FeatureNotSupportedException {
        return parseRangePart(rs, tableEntry, false);
    }

    private static Map<ObPartitionKey, Long> parseFirstPartSets(ResultSet rs, TableEntry tableEntry)
                                                                                                    throws SQLException,
                                                                                                    IllegalArgumentException,
                                                                                                    FeatureNotSupportedException {
        return parseListPartSets(rs, tableEntry, false);
    }

    private static List<ObComparableKV<ObPartitionKey, Long>> parseSubPartRange(ResultSet rs,
                                                                                TableEntry tableEntry)
                                                                                                      throws SQLException,
                                                                                                      IllegalArgumentException,
                                                                                                      FeatureNotSupportedException {
        return parseRangePart(rs, tableEntry, true);
    }

    private static Map<ObPartitionKey, Long> parseSubPartSets(ResultSet rs, TableEntry tableEntry)
                                                                                                  throws SQLException,
                                                                                                  IllegalArgumentException,
                                                                                                  FeatureNotSupportedException {
        return parseListPartSets(rs, tableEntry, true);
    }

    private static List<ObComparableKV<ObPartitionKey, Long>> parseRangePart(ResultSet rs,
                                                                             TableEntry tableEntry,
                                                                             boolean isSubPart)
                                                                                               throws SQLException,
                                                                                               IllegalArgumentException,
                                                                                               FeatureNotSupportedException {
        String partIdColumnName = "part_id";
        ObPartDesc partDesc = tableEntry.getPartitionInfo().getFirstPartDesc();
        if (isSubPart) {
            partIdColumnName = "sub_part_id";
            partDesc = tableEntry.getPartitionInfo().getSubPartDesc();
        }

        List<ObColumn> orderPartColumns = ((ObRangePartDesc) partDesc).getOrderedCompareColumns();
        List<ObComparableKV<ObPartitionKey, Long>> bounds = new ArrayList<ObComparableKV<ObPartitionKey, Long>>();
        Map<String, Long> partNameIdMap = new HashMap<String, Long>();
        while (rs.next()) {
            String highBoundVal = rs.getString("high_bound_val");
            String[] splits = highBoundVal.split(",");
            List<Comparable> partElements = new ArrayList<Comparable>();

            for (int i = 0; i < splits.length; i++) {
                String elementStr = getPlainString(splits[i]);
                if (elementStr.equalsIgnoreCase("MAXVALUE")) {
                    partElements.add(MAX_PARTITION_ELEMENT);
                } else if (elementStr.equalsIgnoreCase("MINVALUE")) {
                    partElements.add(MIN_PARTITION_ELEMENT);
                } else {
                    partElements
                        .add(orderPartColumns
                            .get(i)
                            .getObObjType()
                            .parseToComparable(elementStr,
                                orderPartColumns.get(i).getObCollationType()));
                }
            }
            ObPartitionKey partitionKey = new ObPartitionKey(orderPartColumns, partElements);
            Long partId = rs.getLong(partIdColumnName);
            String partName = rs.getString("part_name");
            bounds.add(new ObComparableKV<ObPartitionKey, Long>(partitionKey, partId));
            partNameIdMap.put(partName.toLowerCase(), partId);
        }
        //set single level partition name-id mapping
        partDesc.setPartNameIdMap(partNameIdMap);
        Collections.sort(bounds);
        return bounds;
    }

    private static Map<ObPartitionKey, Long> parseListPartSets(ResultSet rs, TableEntry tableEntry,
                                                               boolean isSubPart)
                                                                                 throws SQLException,
                                                                                 IllegalArgumentException,
                                                                                 FeatureNotSupportedException {

        String partIdColumnName = "part_id";
        // tableEntry.getPartInfo() will not be null
        ObPartDesc partDesc = tableEntry.getPartitionInfo().getFirstPartDesc();
        if (isSubPart) {
            partIdColumnName = "sub_part_id";
            partDesc = tableEntry.getPartitionInfo().getSubPartDesc();
        }

        List<ObColumn> columns = ((ObListPartDesc) partDesc).getOrderCompareColumns();
        Map<ObPartitionKey, Long> sets = new HashMap<ObPartitionKey, Long>();
        Map<String, Long> partNameIdMap = new HashMap<String, Long>();
        while (rs.next()) {
            // multi-columns: '(1,2),(1,3),(1,4),(default)'
            // single-columns: '(1),(2),(3)' or '1,2,3'
            String setsStr = rs.getString("high_bound_val");
            setsStr = (null == setsStr) ? "" : setsStr.trim();
            if (setsStr.length() < 2) {
                logger.error(LCD.convert("01-00016"), setsStr, tableEntry.toString());
                // if partition value format is wrong, directly throw exception
                throw new IllegalArgumentException(format(
                    "high_bound_val value is error, high_bound_val=%s, tableEntry=%s", setsStr,
                    tableEntry.toString()));
            }
            // skip the first character '(' and the last ')' if exist
            if (setsStr.startsWith("(") && setsStr.endsWith(")")) {
                setsStr = setsStr.substring(1, setsStr.length() - 1);
            }

            String[] setArray = null;
            if (setsStr.contains("),(")) { // multi-column format
                setArray = setsStr.split("\\),\\(");
            } else { // single-column format
                setArray = setsStr.split(",");
            }

            ObPartitionKey key = null;
            Long partId = null;
            String partName = null;
            // setArray can not be null
            for (String set : setArray) {
                if ("default".equalsIgnoreCase(set)) {
                    key = ObPartDesc.DEFAULT_PART_KEY;
                } else {
                    String[] splits = set.split(",");

                    List<Comparable> partElements = new ArrayList<Comparable>();
                    for (int i = 0; i < splits.length; i++) {
                        partElements.add(columns.get(i).getObObjType()
                            .parseToComparable(splits[i], columns.get(i).getObCollationType()));
                    }
                    key = new ObPartitionKey(columns, partElements);
                }
                partId = rs.getLong(partIdColumnName);
                partName = rs.getString("part_name");
                sets.put(key, partId);
                partNameIdMap.put(partName.toLowerCase(), partId);
            }
        }
        //set single level partition name-id mapping
        partDesc.setPartNameIdMap(partNameIdMap);
        return sets;
    }

    /*
     * Load ocp model.
     */
    public static OcpModel loadOcpModel(String paramURL, String dataSourceName, int connectTimeout,
                                        int readTimeout, int retryTimes, long retryInternal)
                                                                                            throws Exception {

        OcpModel ocpModel = new OcpModel();
        List<ObServerAddr> obServerAddrs = new ArrayList<ObServerAddr>();
        ocpModel.setObServerAddrs(obServerAddrs);

        OcpResponse ocpResponse = getRemoteOcpResponseOrNull(paramURL, dataSourceName,
            connectTimeout, readTimeout, retryTimes, retryInternal);

        if (ocpResponse == null && (dataSourceName != null && !dataSourceName.isEmpty())) { // get config from local file
            ocpResponse = getLocalOcpResponseOrNull(dataSourceName);
        }

        if (ocpResponse != null) {
            OcpResponseData ocpResponseData = ocpResponse.getData();
            ocpModel.setClusterId(ocpResponseData.getObRegionId());
            for (OcpResponseDataRs responseRs : ocpResponseData.getRsList()) {
                ObServerAddr obServerAddr = new ObServerAddr();
                obServerAddr.setAddress(responseRs.getAddress());
                obServerAddr.setSqlPort(responseRs.getSql_port());
                obServerAddrs.add(obServerAddr);
            }
        }

        if (obServerAddrs.isEmpty()) {
            throw new RuntimeException("load rs list failed dataSource: " + dataSourceName
                                       + " paramURL:" + paramURL + " response:" + ocpResponse);
        }

        // Get IDC -> Region map if any.
        String obIdcRegionURL = paramURL.replace(Constants.OCP_ROOT_SERVICE_ACTION,
            Constants.OCP_IDC_REGION_ACTION);

        ocpResponse = getRemoteOcpIdcRegionOrNull(obIdcRegionURL, connectTimeout, readTimeout,
            retryTimes, retryInternal);

        if (ocpResponse != null) {
            OcpResponseData ocpResponseData = ocpResponse.getData();
            if (ocpResponseData != null && ocpResponseData.getIDCList() != null) {
                for (OcpResponseDataIDC idcRegion : ocpResponseData.getIDCList()) {
                    ocpModel.addIdc2Region(idcRegion.getIdc(), idcRegion.getRegion());
                }
            }
        }
        return ocpModel;
    }

    private static OcpResponse getRemoteOcpResponseOrNull(String paramURL, String dataSourceName,
                                                          int connectTimeout, int readTimeout,
                                                          int tryTimes, long retryInternal)
                                                                                           throws InterruptedException {

        OcpResponse ocpResponse = null;
        String content = null;
        int tries = 0;
        Exception cause = null;
        for (; tries < tryTimes; tries++) {
            try {
                content = loadStringFromUrl(paramURL, connectTimeout, readTimeout);
                ocpResponse = JSONObject.parseObject(content, OcpResponse.class);
                if (ocpResponse != null && ocpResponse.validate()) {
                    if (dataSourceName != null && !dataSourceName.isEmpty()) {
                        saveLocalContent(dataSourceName, content);
                    }
                    return ocpResponse;
                }
            } catch (Exception e) {
                cause = e;
                logger.error(LCD.convert("01-00017"), e);
                Thread.sleep(retryInternal);
            }
        }

        if (tries >= tryTimes) {
            throw new ObTableRetryExhaustedException("Fail to get OCP response after " + tryTimes
                                                     + " tries from [" + paramURL
                                                     + "], the content is [" + content + "]", cause);
        }
        return null;
    }

    /*
     * Get IdcRegion info from OCP.
     * Return null instead of throwing exception if get nothing, because the info is optional.
     *
     * @param paramURL
     * @param connectTimeout
     * @param readTimeout
     * @param tryTimes
     * @param retryInternal
     * @return
     * @throws InterruptedException
     */
    private static OcpResponse getRemoteOcpIdcRegionOrNull(String paramURL, int connectTimeout,
                                                           int readTimeout, int tryTimes,
                                                           long retryInternal)
                                                                              throws InterruptedException {

        OcpResponse ocpResponse = null;
        String content = null;
        int tries = 0;
        for (; tries < tryTimes; tries++) {
            try {
                content = loadStringFromUrl(paramURL, connectTimeout, readTimeout);
                ocpResponse = JSONObject.parseObject(content, OcpResponse.class);
                if (ocpResponse != null) {
                    return ocpResponse;
                }
            } catch (Exception e) {
                logger.error(LCD.convert("01-00017"), e);
                Thread.sleep(retryInternal);
            }
        }

        if (tries >= tryTimes) {
            logger.error(LCD.convert("01-00017"), "OCP IdcRegion after" + tryTimes
                                                  + " tries from [" + paramURL
                                                  + "], the content is [" + content + "]");
        }
        return null;
    }

    private static OcpResponse parseOcpResponse(String content) throws JSONException {
        return JSONObject.parseObject(content, OcpResponse.class);
    }

    private static OcpResponse getLocalOcpResponseOrNull(String fileName) {
        File file = new File(format("%s/conf/obtable//%s", home, fileName));
        try {
            BufferedInputStream inputStream = null;
            try {
                InputStream fileInputStream;
                if (file.exists()) {
                    fileInputStream = new FileInputStream(file);
                } else {
                    return null;
                }
                inputStream = new BufferedInputStream(fileInputStream);
                byte[] bytes = new byte[inputStream.available()];
                int read = inputStream.read(bytes);
                if (read != bytes.length) {
                    throw new IOException("File bytes invalid: " + fileName);
                }
                String content = new String(bytes);
                return parseOcpResponse(content);
            } finally {
                if (inputStream != null) {
                    inputStream.close();
                }
            }
        } catch (IOException e) {
            logger.warn("load obtable file meet exception: " + file.getAbsolutePath(), e);
            return null;
        }

    }

    private static void saveLocalContent(String fileName, String content) {
        File file = new File(format("%s/conf/obtable/%s", home, fileName));

        // Format content
        try {
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                file.createNewFile();
            }

            BufferedOutputStream outputStream = null;
            try {
                outputStream = new BufferedOutputStream(new FileOutputStream(file));
                outputStream.write(content.getBytes());
            } finally {
                if (outputStream != null) {
                    outputStream.close();
                }
            }
        } catch (IOException e) {
            // 写配置失败需要删除文件，避免写入脏数据
            file.delete();
            logger.warn("Save obtable file meet exception: " + file.getAbsolutePath(), e);
        }
    }

    private static String loadStringFromUrl(String url, int connectTimeout, int readTimeout)
                                                                                            throws Exception {
        HttpURLConnection con = null;
        String content;
        try {
            URL obj = new URL(url);
            con = (HttpURLConnection) obj.openConnection();
            // optional default is GET
            con.setRequestMethod("GET");
            con.setConnectTimeout(connectTimeout);
            con.setReadTimeout(readTimeout);
            con.connect();

            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuilder response = new StringBuilder();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            content = response.toString();
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }

        return content;
    }

    // trim single '
    private static String getPlainString(String str) {
        int start = str.length() > 0 && str.charAt(0) == '\'' ? 1 : 0;
        int end = str.length() > 0 && str.charAt(str.length() - 1) == '\'' ? str.length() - 1 : str
            .length();
        return str.substring(start, end);
    }
}
