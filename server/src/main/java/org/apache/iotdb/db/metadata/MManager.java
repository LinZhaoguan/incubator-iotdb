/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.metadata;

import static java.util.stream.Collectors.toList;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.iotdb.db.conf.IoTDBConfig;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.conf.adapter.ActiveTimeSeriesCounter;
import org.apache.iotdb.db.conf.adapter.IoTDBConfigDynamicAdapter;
import org.apache.iotdb.db.engine.StorageEngine;
import org.apache.iotdb.db.engine.fileSystem.SystemFileFactory;
import org.apache.iotdb.db.exception.ConfigAdjusterException;
import org.apache.iotdb.db.exception.metadata.DeleteFailedException;
import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.metadata.PathNotExistException;
import org.apache.iotdb.db.exception.metadata.StorageGroupNotSetException;
import org.apache.iotdb.db.metadata.mnode.MNode;
import org.apache.iotdb.db.metadata.mnode.MeasurementMNode;
import org.apache.iotdb.db.metadata.mnode.StorageGroupMNode;
import org.apache.iotdb.db.monitor.MonitorConstants;
import org.apache.iotdb.db.qp.constant.SQLConstant;
import org.apache.iotdb.db.qp.physical.crud.InsertPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertRowPlan;
import org.apache.iotdb.db.qp.physical.crud.InsertTabletPlan;
import org.apache.iotdb.db.qp.physical.sys.CreateTimeSeriesPlan;
import org.apache.iotdb.db.qp.physical.sys.ShowTimeSeriesPlan;
import org.apache.iotdb.db.query.context.QueryContext;
import org.apache.iotdb.db.query.dataset.ShowTimeSeriesResult;
import org.apache.iotdb.db.utils.RandomDeleteCache;
import org.apache.iotdb.db.utils.TestOnly;
import org.apache.iotdb.db.utils.TypeInferenceUtils;
import org.apache.iotdb.tsfile.common.conf.TSFileDescriptor;
import org.apache.iotdb.tsfile.exception.cache.CacheException;
import org.apache.iotdb.tsfile.exception.write.UnSupportedDataTypeException;
import org.apache.iotdb.tsfile.file.metadata.enums.CompressionType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.read.TimeValuePair;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.utils.Pair;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.apache.iotdb.tsfile.write.schema.TimeseriesSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class takes the responsibility of serialization of all the metadata info and persistent it
 * into files. This class contains all the interfaces to modify the metadata for delta system. All
 * the operations will be insert into the logs temporary in case the downtime of the delta system.
 */
public class MManager {

  private static final Logger logger = LoggerFactory.getLogger(MManager.class);
  private static final String TIME_SERIES_TREE_HEADER = "===  Timeseries Tree  ===\n\n";

  /**
   * A thread will check whether the MTree is modified lately each such interval. Unit: second
   */
  private static final long MTREE_SNAPSHOT_THREAD_CHECK_TIME = 600L;

  // the lock for read/insert
  private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  // the log file seriesPath
  private String logFilePath;
  private String mtreeSnapshotPath;
  private String mtreeSnapshotTmpPath;
  private MTree mtree;
  private MLogWriter logWriter;
  private TagLogFile tagLogFile;
  private boolean isRecovering;
  // device -> DeviceMNode
  private RandomDeleteCache<String, MNode> mNodeCache;

  // tag key -> tag value -> LeafMNode
  private Map<String, Map<String, Set<MeasurementMNode>>> tagIndex = new HashMap<>();

  // storage group name -> the series number
  private Map<String, Integer> seriesNumberInStorageGroups = new HashMap<>();
  private long maxSeriesNumberAmongStorageGroup;
  private boolean initialized;
  protected IoTDBConfig config;

  private File logFile;
  private final int mtreeSnapshotInterval;
  private final long mtreeSnapshotThresholdTime;
  private ScheduledExecutorService timedCreateMTreeSnapshotThread;

  private static class MManagerHolder {

    private MManagerHolder() {
      // allowed to do nothing
    }

    private static final MManager INSTANCE = new MManager();
  }

  protected MManager() {
    config = IoTDBDescriptor.getInstance().getConfig();
    mtreeSnapshotInterval = config.getMtreeSnapshotInterval();
    mtreeSnapshotThresholdTime = config.getMtreeSnapshotThresholdTime() * 1000L;
    String schemaDir = config.getSchemaDir();
    File schemaFolder = SystemFileFactory.INSTANCE.getFile(schemaDir);
    if (!schemaFolder.exists()) {
      if (schemaFolder.mkdirs()) {
        logger.info("create system folder {}", schemaFolder.getAbsolutePath());
      } else {
        logger.info("create system folder {} failed.", schemaFolder.getAbsolutePath());
      }
    }
    logFilePath = schemaDir + File.separator + MetadataConstant.METADATA_LOG;
    mtreeSnapshotPath = schemaDir + File.separator + MetadataConstant.MTREE_SNAPSHOT;
    mtreeSnapshotTmpPath = schemaDir + File.separator + MetadataConstant.MTREE_SNAPSHOT_TMP;

    // do not write log when recover
    isRecovering = true;

    int cacheSize = config.getmManagerCacheSize();
    mNodeCache = new RandomDeleteCache<String, MNode>(cacheSize){
      @Override
      public MNode loadObjectByKey(String key, List<String> keyNodes) throws CacheException {
        lock.readLock().lock();
        try {
          return mtree.getMNodeByDetachedPathWithStorageGroupCheck(keyNodes);
        } catch (MetadataException e) {
          throw new CacheException(e);
        } finally {
          lock.readLock().unlock();
        }
      }
    };

    timedCreateMTreeSnapshotThread = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r,
        "timedCreateMTreeSnapshotThread"));
    timedCreateMTreeSnapshotThread
        .scheduleAtFixedRate(this::checkMTreeModified, MTREE_SNAPSHOT_THREAD_CHECK_TIME,
            MTREE_SNAPSHOT_THREAD_CHECK_TIME, TimeUnit.SECONDS);
  }

  /**
   * we should not use this function in other place, but only in IoTDB class
   * @return MManager instance
   */
  public static MManager getInstance() {
    return MManagerHolder.INSTANCE;
  }

  // Because the writer will be used later and should not be closed here.
  @SuppressWarnings("squid:S2093")
  public synchronized void init() {
    if (initialized) {
      return;
    }
    logFile = SystemFileFactory.INSTANCE.getFile(logFilePath);

    try {
      tagLogFile = new TagLogFile(config.getSchemaDir(), MetadataConstant.TAG_LOG);

      isRecovering = true;
      int lineNumber = initFromLog(logFile);

      if (config.isEnableParameterAdapter()) {
        List<String> storageGroups = mtree.getAllDetachedStorageGroups();
        for (String sg : storageGroups) {
          MNode node = mtree.getMNodeByDetachedPath(MetaUtils.splitPathToDetachedPath(sg));
          seriesNumberInStorageGroups.put(sg, node.getLeafCount());
        }
        maxSeriesNumberAmongStorageGroup =
            seriesNumberInStorageGroups.values().stream().max(Integer::compareTo).orElse(0);
      }

      logWriter = new MLogWriter(config.getSchemaDir(), MetadataConstant.METADATA_LOG);
      logWriter.setLineNumber(lineNumber);
      isRecovering = false;
    } catch (IOException | MetadataException e) {
      mtree = new MTree();
      logger.error("Cannot read MTree from file, using an empty new one", e);
    }
    initialized = true;
  }

  /**
   * @return line number of the logFile
   */
  @SuppressWarnings("squid:S3776")
  private int initFromLog(File logFile) throws IOException {
    File tmpFile = SystemFileFactory.INSTANCE.getFile(mtreeSnapshotTmpPath);
    if (tmpFile.exists()) {
      logger.warn("Creating MTree snapshot not successful before crashing...");
      Files.delete(tmpFile.toPath());
    }

    File mtreeSnapshot = SystemFileFactory.INSTANCE.getFile(mtreeSnapshotPath);
    long time = System.currentTimeMillis();
    if (!mtreeSnapshot.exists()) {
      mtree = new MTree();
    } else {
      mtree = MTree.deserializeFrom(mtreeSnapshot);
      logger.debug("spend {} ms to deserialize mtree from snapshot",
          System.currentTimeMillis() - time);
    }

    time = System.currentTimeMillis();
    // init the metadata from the operation log
    if (logFile.exists()) {
      int idx = 0;
      try (FileReader fr = new FileReader(logFile);
          BufferedReader br = new BufferedReader(fr)) {
        String cmd;
        while ((cmd = br.readLine()) != null) {
          try {
            operation(cmd);
            idx++;
          } catch (Exception e) {
            logger.error("Can not operate cmd {}", cmd, e);
          }
        }
      }
      logger.debug("spend {} ms to deserialize mtree from mlog.txt",
          System.currentTimeMillis() - time);
      return idx;
    } else if (mtreeSnapshot.exists()) {
      throw new IOException("mtree snapshot file exists but mlog.txt does not exist.");
    } else {
      return 0;
    }
  }

  /**
   * function for clearing MTree
   */
  public void clear() {
    lock.writeLock().lock();
    try {
      this.mtree = new MTree();
      this.mNodeCache.clear();
      this.tagIndex.clear();
      this.seriesNumberInStorageGroups.clear();
      this.maxSeriesNumberAmongStorageGroup = 0;
      if (logWriter != null) {
        logWriter.close();
        logWriter = null;
      }
      if (tagLogFile != null) {
        tagLogFile.close();
        tagLogFile = null;
      }
      initialized = false;
      if (timedCreateMTreeSnapshotThread != null) {
        timedCreateMTreeSnapshotThread.shutdownNow();
        timedCreateMTreeSnapshotThread = null;
      }
    } catch (IOException e) {
      logger.error("Cannot close metadata log writer, because:", e);
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void operation(String cmd) throws IOException, MetadataException {
    // see createTimeseries() to get the detailed format of the cmd
    String[] args = cmd.trim().split(",", -1);
    switch (args[0]) {
      case MetadataOperationType.CREATE_TIMESERIES:
        Map<String, String> props = null;
        if (!args[5].isEmpty()) {
          String[] keyValues = args[5].split("&");
          String[] kv;
          props = new HashMap<>();
          for (String keyValue : keyValues) {
            kv = keyValue.split("=");
            props.put(kv[0], kv[1]);
          }
        }

        String alias = null;
        if (!args[6].isEmpty()) {
          alias = args[6];
        }
        long offset = -1L;
        Map<String, String> tagMap = null;
        if (!args[7].isEmpty()) {
          offset = Long.parseLong(args[7]);
          tagMap = tagLogFile.readTag(config.getTagAttributeTotalSize(), offset);
        }

        CreateTimeSeriesPlan plan = new CreateTimeSeriesPlan(new Path(MetaUtils.splitPathToDetachedPath(args[1])),
            TSDataType.deserialize(Short.parseShort(args[2])),
            TSEncoding.deserialize(Short.parseShort(args[3])),
            CompressionType.deserialize(Short.parseShort(args[4])), props, tagMap, null, alias);

        createTimeseries(plan, offset);
        break;
      case MetadataOperationType.DELETE_TIMESERIES:
        String failedTimeseries = deleteTimeseries(MetaUtils.splitPathToDetachedPath(args[1]));
        if (!failedTimeseries.isEmpty()) {
          throw new DeleteFailedException(failedTimeseries);
        }
        break;
      case MetadataOperationType.SET_STORAGE_GROUP:
        setStorageGroup(MetaUtils.splitPathToDetachedPath(args[1]));
        break;
      case MetadataOperationType.DELETE_STORAGE_GROUP:
        List<String> storageGroups = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
        List<List<String>> storageGroupNodesList = new ArrayList<>();
        for(String storageGroup : storageGroups) {
          storageGroupNodesList.add(MetaUtils.splitPathToDetachedPath(storageGroup));
        }
        deleteStorageGroups(storageGroupNodesList);
        break;
      case MetadataOperationType.SET_TTL:
        setTTL(MetaUtils.splitPathToDetachedPath(args[1]), Long.parseLong(args[2]));
        break;
      case MetadataOperationType.CHANGE_OFFSET:
        changeOffset(MetaUtils.splitPathToDetachedPath(args[1]), Long.parseLong(args[2]));
        break;
      case MetadataOperationType.CHANGE_ALIAS:
        changeAlias(MetaUtils.splitPathToDetachedPath(args[1]), args[2]);
        break;
      default:
        logger.error("Unrecognizable command {}", cmd);
    }
  }

  public void createTimeseries(CreateTimeSeriesPlan plan) throws MetadataException {
    createTimeseries(plan, -1);
  }

  public void createTimeseries(CreateTimeSeriesPlan plan, long offset) throws MetadataException {
    lock.writeLock().lock();
    List<String> detachedPath = plan.getPath().getDetachedPath();
    try {
      /*
       * get the storage group with auto create schema
       */
      String storageGroupPath = null;
      try {
        storageGroupPath = mtree.getStorageGroup(detachedPath);
      } catch (StorageGroupNotSetException e) {
        if (!config.isAutoCreateSchemaEnabled()) {
          throw e;
        }
        List<String> detachedStorageGroupPath =
            MetaUtils.getDetachedStorageGroupByLevel(detachedPath, config.getDefaultStorageGroupLevel());
        setStorageGroup(detachedStorageGroupPath);
      }

      // check memory
      IoTDBConfigDynamicAdapter.getInstance().addOrDeleteTimeSeries(1);

      // create time series in MTree
      MeasurementMNode leafMNode = mtree
          .createTimeseries(detachedPath, plan.getDataType(), plan.getEncoding(), plan.getCompressor(),
              plan.getProps(), plan.getAlias());

      // update tag index
      if (plan.getTags() != null) {
        // tag key, tag value
        for (Entry<String, String> entry : plan.getTags().entrySet()) {
          tagIndex.computeIfAbsent(entry.getKey(), k -> new HashMap<>())
              .computeIfAbsent(entry.getValue(), v -> new HashSet<>()).add(leafMNode);
        }
      }

      // update statistics
      if (config.isEnableParameterAdapter()) {
        int size = seriesNumberInStorageGroups.get(storageGroupPath);
        seriesNumberInStorageGroups.put(storageGroupPath, size + 1);
        if (size + 1 > maxSeriesNumberAmongStorageGroup) {
          maxSeriesNumberAmongStorageGroup = size + 1;
        }
      }

      // write log
      if (!isRecovering) {
        // either tags or attributes is not empty
        if ((plan.getTags() != null && !plan.getTags().isEmpty())
            || (plan.getAttributes() != null && !plan.getAttributes().isEmpty())) {
          offset = tagLogFile.write(plan.getTags(), plan.getAttributes());
        }
        logWriter.createTimeseries(plan, offset);
      }
      leafMNode.setOffset(offset);

    } catch (IOException | ConfigAdjusterException e) {
      throw new MetadataException(e.getMessage());
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Add one timeseries to metadata tree, if the timeseries already exists, throw exception
   *
   * @param detachedPath detachedPath of the timeseries path
   * @param dataType the dateType {@code DataType} of the timeseries
   * @param encoding the encoding function {@code Encoding} of the timeseries
   * @param dataType   the dateType {@code DataType} of the timeseries
   * @param encoding   the encoding function {@code Encoding} of the timeseries
   * @param compressor the compressor function {@code Compressor} of the time series
   * @return whether the measurement occurs for the first time in this storage group (if true, the
   * measurement should be registered to the StorageEngine too)
   */
  public void createTimeseries(List<String> detachedPath, TSDataType dataType, TSEncoding encoding,
      CompressionType compressor, Map<String, String> props) throws MetadataException {
    createTimeseries(
        new CreateTimeSeriesPlan(new Path(detachedPath), dataType, encoding, compressor, props, null, null,
            null));
  }

  /**
   * Delete all timeseries under the given path, may cross different storage group
   *
   * @param detachedPath path to be deleted, could be root or a prefix path or a full path
   * @return The String is the deletion failed Timeseries
   */
  public String deleteTimeseries(List<String> detachedPath) throws MetadataException {
    lock.writeLock().lock();

    if (isStorageGroup(detachedPath)) {

      if (config.isEnableParameterAdapter()) {
        int size = seriesNumberInStorageGroups.get(MetaUtils.concatDetachedPathByDot(detachedPath));
        seriesNumberInStorageGroups.put(MetaUtils.concatDetachedPathByDot(detachedPath), 0);
        if (size == maxSeriesNumberAmongStorageGroup) {
          seriesNumberInStorageGroups.values().stream()
              .max(Integer::compareTo)
              .ifPresent(val -> maxSeriesNumberAmongStorageGroup = val);
        }
      }

      mNodeCache.clear();
    }
    try {
      List<MeasurementMNode> allMeasurementMNodes = mtree.getAllMeasurementMNodes(detachedPath);
      // Monitor storage group seriesPath is not allowed to be deleted
      allMeasurementMNodes.removeIf(p -> p.getFullPath().contains(MonitorConstants.STATS));

      Set<String> failedNames = new HashSet<>();
      for (MeasurementMNode n : allMeasurementMNodes) {
        try {
          String emptyStorageGroup = deleteOneTimeseriesAndUpdateStatistics(n);
          if (!isRecovering) {
            if (emptyStorageGroup != null) {
              StorageEngine.getInstance().deleteAllDataFilesInOneStorageGroup(emptyStorageGroup);
            }
            logWriter.deleteTimeseries(n.getFullPath());
          }
        } catch (DeleteFailedException e) {
          failedNames.add(e.getName());
        }
      }
      return String.join(",", failedNames);
    } catch (IOException e) {
      throw new MetadataException(e.getMessage());
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * remove the node from the tag inverted index
   */
  private void removeFromTagInvertedIndex(MeasurementMNode mNode) throws IOException {
    if (mNode.getOffset() < 0) {
      return;
    }
    Map<String, String> tagMap =
        tagLogFile.readTag(config.getTagAttributeTotalSize(), mNode.getOffset());
    if (tagMap != null) {
      for (Entry<String, String> entry : tagMap.entrySet()) {
        if (tagIndex.containsKey(entry.getKey()) && tagIndex.get(entry.getKey())
            .containsKey(entry.getValue())) {
          if (logger.isDebugEnabled()) {
            logger.debug(String.format(
                "Delete: TimeSeries %s is removed from tag inverted index, "
                    + "tag key is %s, tag value is %s, tlog offset is %d",
                mNode.getFullPath(), entry.getKey(), entry.getValue(), mNode.getOffset()));
          }
          tagIndex.get(entry.getKey()).get(entry.getValue()).remove(mNode);
          if (tagIndex.get(entry.getKey()).get(entry.getValue()).isEmpty()) {
            tagIndex.get(entry.getKey()).remove(entry.getValue());
            if (tagIndex.get(entry.getKey()).isEmpty()) {
              tagIndex.remove(entry.getKey());
            }
          }
        } else {
          if (logger.isDebugEnabled()) {
            logger.debug(String.format(
                "Delete: TimeSeries %s's tag info has been removed from tag inverted index before "
                    + "deleting it, tag key is %s, tag value is %s, tlog offset is %d, contains key %b",
                mNode.getFullPath(), entry.getKey(), entry.getValue(), mNode.getOffset(),
                tagIndex.containsKey(entry.getKey())));
          }
        }
      }
    }
  }

  /**
   * @param measurementMNode detachedPath of full path from root to leaf node
   * @return after delete if the storage group is empty, return its name, otherwise return null
   */
  private String deleteOneTimeseriesAndUpdateStatistics(MeasurementMNode measurementMNode)
      throws MetadataException, IOException {
    lock.writeLock().lock();
    try {
      Pair<String, MeasurementMNode> pair = mtree.deleteTimeseriesAndReturnEmptyStorageGroup(measurementMNode);
      removeFromTagInvertedIndex(pair.right);
      String storageGroupName = pair.left;

      // TODO: delete the path node and all its ancestors
      mNodeCache.clear();
      try {
        IoTDBConfigDynamicAdapter.getInstance().addOrDeleteTimeSeries(-1);
      } catch (ConfigAdjusterException e) {
        throw new MetadataException(e);
      }

      if (config.isEnableParameterAdapter()) {
        String storageGroup = getStorageGroupMNodeByMNode(measurementMNode).getFullPath();
        int size = seriesNumberInStorageGroups.get(storageGroup);
        seriesNumberInStorageGroups.put(storageGroup, size - 1);
        if (size == maxSeriesNumberAmongStorageGroup) {
          seriesNumberInStorageGroups.values().stream().max(Integer::compareTo)
              .ifPresent(val -> maxSeriesNumberAmongStorageGroup = val);
        }
      }
      return storageGroupName;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Set storage group of the given path to MTree. Check
   *
   * @param detachedPath detachedPath
   */
  public void setStorageGroup(List<String> detachedPath) throws MetadataException {
    lock.writeLock().lock();
    try {
      MNode mNode = mtree.setStorageGroup(detachedPath);
      IoTDBConfigDynamicAdapter.getInstance().addOrDeleteStorageGroup(1);

      if (config.isEnableParameterAdapter()) {
        ActiveTimeSeriesCounter.getInstance().init(mNode.getFullPath());
        seriesNumberInStorageGroups.put(mNode.getFullPath(), 0);
      }
      if (!isRecovering) {
        logWriter.setStorageGroup(mNode.getFullPath());
      }
    } catch (IOException e) {
      throw new MetadataException(e.getMessage());
    } catch (ConfigAdjusterException e) {
      mtree.deleteStorageGroup(detachedPath);
      throw new MetadataException(e);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Delete storage groups of given paths from MTree. Log format: "delete_storage_group,sg1,sg2,sg3"
   *
   * @param detachedStorageGroups list of paths to be deleted. Format: root.node
   */
  public void deleteStorageGroups(List<List<String>> detachedStorageGroups) throws MetadataException {
    lock.writeLock().lock();
    try {
      for (List<String> detachedStorageGroup : detachedStorageGroups) {

        // clear cached MNode
        mNodeCache.clear();

        // try to delete storage group
        List<MeasurementMNode> leafMNodes = mtree.deleteStorageGroup(detachedStorageGroup);
        for (MeasurementMNode leafMNode : leafMNodes) {
          removeFromTagInvertedIndex(leafMNode);
        }

        if (config.isEnableParameterAdapter()) {
          IoTDBConfigDynamicAdapter.getInstance().addOrDeleteStorageGroup(-1);
          int size = seriesNumberInStorageGroups.get(MetaUtils.concatDetachedPathByDot(detachedStorageGroup));
          IoTDBConfigDynamicAdapter.getInstance().addOrDeleteTimeSeries(size * -1);
          ActiveTimeSeriesCounter.getInstance().delete(MetaUtils.concatDetachedPathByDot(detachedStorageGroup));
          seriesNumberInStorageGroups.remove(MetaUtils.concatDetachedPathByDot(detachedStorageGroup));
          if (size == maxSeriesNumberAmongStorageGroup) {
            maxSeriesNumberAmongStorageGroup =
                seriesNumberInStorageGroups.values().stream().max(Integer::compareTo).orElse(0);
          }
        }
        // if success
        if (!isRecovering) {
          logWriter.deleteStorageGroup(MetaUtils.concatDetachedPathByDot(detachedStorageGroup));
        }
      }
    } catch (ConfigAdjusterException e) {
      throw new MetadataException(e);
    } catch (IOException e) {
      throw new MetadataException(e.getMessage());
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Check if the given path is storage group or not.
   *
   * @param detachedPath Format: [root, node]
   * @apiNote :for cluster
   */
  boolean isStorageGroup(List<String> detachedPath) {
    lock.readLock().lock();
    try {
      return mtree.isStorageGroup(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }


  /**
   * Get series type for given seriesPath.
   *
   * @param detachedPath detachedPath of full path
   */
  public TSDataType getSeriesType(List<String> detachedPath) throws MetadataException {
    lock.readLock().lock();
    try {
      if (detachedPath.get(0).equals(SQLConstant.RESERVED_TIME)) {
        return TSDataType.INT64;
      }

      return mtree.getSchema(detachedPath).getType();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get series type for given measurementMNode
   *
   * @param measurementMNode measurementMNode
   */
  public TSDataType getSeriesTypeByMNode(MeasurementMNode measurementMNode) {
    lock.readLock().lock();
    try {
      return mtree.getSchemaByMNode(measurementMNode).getType();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get node name by given MNode
   * @param mNode mNode
   * @return node name
   */
  public String getMNodeName(MNode mNode) {
    lock.readLock().lock();
    try {
      return mNode.getName();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get detached path by give MNode
   * @param mNode mNode
   * @return node name
   */
  public List<String> getDetachedPathByMnode(MNode mNode) {
    lock.readLock().lock();
    try {
      return mtree.getDetachedPathByMNode(mNode);
    } finally {
      lock.readLock().unlock();
    }
  }


  public MeasurementSchema[] getSchemas(List<String> detachedDevice, String[] measurements)
      throws MetadataException {
    lock.readLock().lock();
    try {
      MNode deviceNode = getMNodeByDetachedPath(detachedDevice);
      MeasurementSchema[] measurementSchemas = new MeasurementSchema[measurements.length];
      for (int i = 0; i < measurementSchemas.length; i++) {
        if (!deviceNode.hasChild(measurements[i])) {
          throw new MetadataException(measurements[i] + " does not exist in " + MetaUtils.concatDetachedPathByDot(detachedDevice));
        }
        measurementSchemas[i] = ((MeasurementMNode) deviceNode.getChild(measurements[i]))
            .getSchema();
      }
      return measurementSchemas;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get all devices under given prefixPath.
   *
   * @param detachedPath detachedPath of a prefix of a full path. if the wildcard is not at the tail, then each
   * wildcard can only match one level, otherwise it can match to the tail.
   * @return A HashSet instance which stores devices names with given prefixPath.
   */
  public Set<Path> getDevicePaths(List<String> detachedPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getDevicesPath(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get all devices under given prefixPath.
   *
   * @param detachedPath detachedPath of a prefix of a full path. if the wildcard is not at the tail, then each
   * wildcard can only match one level, otherwise it can match to the tail.
   * @return A HashSet instance which stores devices names with given prefixPath.
   */
  public Set<String> getDevices(List<String> detachedPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getDevices(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get all detachedPath from the given level
   *
   * @param detachedPath can be detachedPath of a prefixPath or a full path. Can not be a full path. can not have
   *                   wildcard. But, the level of the prefixPath can be smaller than the given
   *                   level, e.g., prefixPath = root.a while the given level is 5
   * @param nodeLevel  the level can not be smaller than the level of the prefixPath
   * @return A List instance which stores all node at given level
   */
  public List<String> getNodeNamesList(List<String> detachedPath, int nodeLevel) throws MetadataException {
    return getNodeNamesList(detachedPath, nodeLevel, null);
  }

  public List<String> getNodeNamesList(List<String> detachedPath, int nodeLevel, StorageGroupFilter filter)
      throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getNodeNamesList(detachedPath, nodeLevel, filter);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get storage group name by path
   *
   * <p>e.g., root.sg1 is a storage group and path = root.sg1.d1, return root.sg1
   *
   * @return storage group in the given path
   */
  public String getStorageGroup(List<String> detachedPath) throws StorageGroupNotSetException {
    lock.readLock().lock();
    try {
      return mtree.getStorageGroup(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  public StorageGroupMNode getStorageGroupMNodeByMNode(MNode mNode) {
    lock.readLock().lock();
    try {
      return mtree.getStorageGroupMNodeByMNode(mNode);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get storage group detachedPath by detachedPath
   *
   * <p>e.g., root.sg1 is a storage group and detachedPath = [root, sg1, d1], return [root, sg1]
   *
   * @return storage group in the given detachedPath
   */
  public List<String> getDetachedStorageGroup(List<String> detachedPath) throws StorageGroupNotSetException {
    lock.readLock().lock();
    try {
      return mtree.getDetachedStorageGroup(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get all storage group names
   */
  public List<String> getAllDetachedStorageGroups() {
    lock.readLock().lock();
    try {
      return mtree.getAllDetachedStorageGroups();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get all storage group MNodes
   */
  public List<StorageGroupMNode> getAllStorageGroupMNodes() {
    lock.readLock().lock();
    try {
      return mtree.getAllStorageGroupMNodes();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Return all paths for given path if the path is abstract. Or return the path itself. Regular
   * expression in this method is formed by the amalgamation of seriesPath and the character '*'.
   *
   * @param prefixPath can be a prefix or a full path. if the wildcard is not at the tail, then each
   *                   wildcard can only match one level, otherwise it can match to the tail.
   */
  public List<String> getAllTimeseries(String prefixPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getAllTimeseries(prefixPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Return all paths for given path if the path is abstract. Or return the path itself. Regular
   * expression in this method is formed by the amalgamation of seriesPath and the character '*'.
   *
   * @param detachedPath can be detachedPath of prefix or a full path. if the wildcard is not at the tail, then each
   * wildcard can only match one level, otherwise it can match to the tail.
   */
  public List<String> getAllTimeseries(List<String> detachedPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getAllTimeseries(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Return all paths for given path if the path is abstract. Or return the path itself. Regular
   * expression in this method is formed by the amalgamation of seriesPath and the character '*'.
   *
   * @param detachedPath can be a prefix or a full path. if the wildcard is not at the tail, then each
   * wildcard can only match one level, otherwise it can match to the tail.
   */
  public List<MeasurementMNode> getAllMeasurementMNodes(List<String> detachedPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getAllMeasurementMNodes(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }


  /**
   * Similar to method getAllMeasurementMNodes(), but return Path instead of String in order to include
   * alias.
   */
  public List<Path> getAllTimeseriesPath(String prefixPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getAllTimeseriesPath(prefixPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * use detachedPath to get All Timeseries
   * @param detachedPath a node list
   */

  public List<Path> getAllTimeseriesPath(List<String> detachedPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getAllTimeseriesPath(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * To calculate the count of timeseries for given prefix path.
   */
  public int getAllTimeseriesCount(List<String> detachedPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getAllTimeseriesCount(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * To calculate the count of detachedPath in the given level for given prefix path.
   *
   * @param detachedPath detachedPath of a prefix path or a full path, can not contain '*'
   * @param level      the level can not be smaller than the level of the prefixPath
   */
  public int getNodesCountInGivenLevel(List<String> detachedPath, int level) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getNodesCountInGivenLevel(detachedPath, level);
    } finally {
      lock.readLock().unlock();
    }
  }

  private List<ShowTimeSeriesResult> showTimeseriesWithIndex(ShowTimeSeriesPlan plan,
      QueryContext context) throws MetadataException {
    lock.readLock().lock();
    try {
      if (!tagIndex.containsKey(plan.getKey())) {
        throw new MetadataException("The key " + plan.getKey() + " is not a tag.");
      }
      Map<String, Set<MeasurementMNode>> value2Node = tagIndex.get(plan.getKey());
      if (value2Node.isEmpty()) {
        throw new MetadataException("The key " + plan.getKey() + " is not a tag.");
      }

      List<MeasurementMNode> allMatchedMNodes = new ArrayList<>();
      if (plan.isContains()) {
        for (Entry<String, Set<MeasurementMNode>> entry : value2Node.entrySet()) {
          String tagValue = entry.getKey();
          if (tagValue.contains(plan.getValue())) {
            allMatchedMNodes.addAll(entry.getValue());
          }
        }
      } else {
        for (Entry<String, Set<MeasurementMNode>> entry : value2Node.entrySet()) {
          String tagValue = entry.getKey();
          if (plan.getValue().equals(tagValue)) {
            allMatchedMNodes.addAll(entry.getValue());
          }
        }
      }

      // if ordered by heat, we sort all the timeseries by the descending order of the last insert timestamp
      if (plan.isOrderByHeat()) {
        allMatchedMNodes = allMatchedMNodes.stream().sorted(Comparator
            .comparingLong((MeasurementMNode mNode) -> MTree.getLastTimeStamp(mNode, context))
            .reversed().thenComparing(MNode::getFullPath)).collect(toList());
      } else {
        // otherwise, we just sort them by the alphabetical order
        allMatchedMNodes = allMatchedMNodes.stream().sorted(Comparator.comparing(MNode::getFullPath))
            .collect(toList());
      }

      List<ShowTimeSeriesResult> res = new LinkedList<>();
      List<String> prefixNodes = plan.getPath().getDetachedPath();
      int curOffset = -1;
      int count = 0;
      int limit = plan.getLimit();
      int offset = plan.getOffset();
      for (MeasurementMNode leaf : allMatchedMNodes) {
        MNode temp = leaf;
        List<String> path = new ArrayList<>();
        path.add(temp.getName());
        while (temp.getParent() != null) {
          temp = temp.getParent();
          path.add(0, temp.getName());
        }
        if (match(path, prefixNodes)) {
          if (limit != 0 || offset != 0) {
            curOffset++;
            if (curOffset < offset || count == limit) {
              continue;
            }
          }
          try {
            Pair<Map<String, String>, Map<String, String>> pair =
                tagLogFile.read(config.getTagAttributeTotalSize(), leaf.getOffset());
            pair.left.putAll(pair.right);
            MeasurementSchema measurementSchema = leaf.getSchema();
            res.add(new ShowTimeSeriesResult(leaf.getFullPath(), leaf.getAlias(),
                getStorageGroup(path), measurementSchema.getType().toString(),
                measurementSchema.getEncodingType().toString(),
                measurementSchema.getCompressor().toString(), pair.left));
            if (limit != 0) {
              count++;
            }
          } catch (IOException e) {
            throw new MetadataException(
                "Something went wrong while deserialize tag info of " + leaf.getFullPath(), e);
          }
        }
      }
      return res;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * whether the full path has the prefixNodes
   */
  private boolean match(List<String> detachedFullPath, List<String> detachedPrefixPath) {
    if (detachedFullPath.size() < detachedPrefixPath.size()) {
      return false;
    }
    for (int i = 0; i < detachedPrefixPath.size(); i++) {
      if (!"*".equals(detachedPrefixPath.get(i)) && !detachedPrefixPath.get(i).equals(detachedFullPath.get(i))) {
        return false;
      }
    }
    return true;
  }

  public List<ShowTimeSeriesResult> showTimeseries(ShowTimeSeriesPlan plan, QueryContext context)
      throws MetadataException {
    // show timeseries with index
    if (plan.getKey() != null && plan.getValue() != null) {
      return showTimeseriesWithIndex(plan, context);
    } else {
      return showTimeseriesWithoutIndex(plan, context);
    }
  }

  /**
   * Get the result of ShowTimeseriesPlan
   *
   * @param plan show time series query plan
   */
  private List<ShowTimeSeriesResult> showTimeseriesWithoutIndex(ShowTimeSeriesPlan plan,
      QueryContext context) throws MetadataException {
    lock.readLock().lock();
    List<String[]> ans;
    try {
      if (plan.isOrderByHeat()) {
        ans = mtree.getAllMeasurementSchemaByHeatOrder(plan, context);
      } else {
        ans = mtree.getAllMeasurementSchema(plan);
      }
      List<ShowTimeSeriesResult> res = new LinkedList<>();
      for (String[] ansString : ans) {
        long tagFileOffset = Long.parseLong(ansString[6]);
        try {
          if (tagFileOffset < 0) {
            // no tags/attributes
            res.add(new ShowTimeSeriesResult(ansString[0], ansString[1], ansString[2], ansString[3],
                ansString[4], ansString[5], Collections.emptyMap()));
          } else {
            // has tags/attributes
            Pair<Map<String, String>, Map<String, String>> pair =
                tagLogFile.read(config.getTagAttributeTotalSize(), tagFileOffset);
            pair.left.putAll(pair.right);
            res.add(new ShowTimeSeriesResult(ansString[0], ansString[1], ansString[2], ansString[3],
                ansString[4], ansString[5], pair.left));
          }
        } catch (IOException e) {
          throw new MetadataException(
              "Something went wrong while deserialize tag info of " + ansString[0], e);
        }
      }
      return res;
    } finally {
      lock.readLock().unlock();
    }
  }

  public MeasurementSchema getSeriesSchema(List<String> detachedDevice, String measurement)
      throws MetadataException {
    lock.readLock().lock();
    try {
      MNode node = mtree.getMNodeByDetachedPath(detachedDevice);
      MNode leaf = node.getChild(measurement);
      if (leaf != null) {
        return ((MeasurementMNode) leaf).getSchema();
      }
      return null;
    } catch (PathNotExistException | IllegalPathException e) {
      //do nothing and throw it directly.
      throw e;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get child node path in the next level of the given path.
   *
   * <p>e.g., MTree has [root.sg1.d1.s1, root.sg1.d1.s2, root.sg1.d2.s1] given path = root.sg1,
   * return [root.sg1.d1, root.sg1.d2]
   *
   * @return All child detachedPath' seriesPath(s) of given seriesPath.
   */
  public Set<String> getChildPathInNextLevel(List<String> detachedPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getChildPathInNextLevel(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Check whether the path exists.
   *
   * @param detachedPath detachedPath of a full path or a prefix path
   */
  public boolean isPathExist(List<String> detachedPath) {
    lock.readLock().lock();
    try {
      return mtree.isPathExist(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get node by detachedPath of path
   */
  public MNode getMNodeByDetachedPath(List<String> detachedPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getMNodeByDetachedPath(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get storage group node by path. If storage group is not set, StorageGroupNotSetException will
   * be thrown
   */
  public StorageGroupMNode getStorageGroupMNode(List<String> detachedPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getStorageGroupMNode(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * get device node, if the storage group is not set, create it when autoCreateSchema is true <p>
   * (we develop this method as we need to get the node's lock after we get the lock.writeLock())
   *
   * <p>!!!!!!Attention!!!!! must call the return node's readUnlock() if you call this method.
   *
   * @param deviceId full path of device
   * @param detachedDevice a list of device detachedPath
   */
  public MNode getDeviceMNodeWithAutoCreateAndReadLock(
      String deviceId, List<String> detachedDevice, boolean autoCreateSchema, int sgLevel) throws MetadataException {

    lock.readLock().lock();
    MNode node = null;
    boolean shouldSetStorageGroup = false;
    try {
      node = mNodeCache.get(deviceId, detachedDevice);
      return node;
    } catch (CacheException e) {
      if(e.getCause() instanceof PathNotExistException) {
        if (!autoCreateSchema) {
          throw new PathNotExistException(deviceId);
        }
      }
      if(e.getCause() instanceof  StorageGroupNotSetException) {
        shouldSetStorageGroup = true;
      }
    } finally {
      if (node != null) {
        node.readLock();
      }
      lock.readLock().unlock();
    }

    lock.writeLock().lock();
    try {
      if(shouldSetStorageGroup) {
        List<String> storageGroupName = MetaUtils.getDetachedStorageGroupByLevel(detachedDevice, sgLevel);
        setStorageGroup(storageGroupName);
      }
      node = mNodeCache.get(deviceId, detachedDevice);
      return node;
    } catch (CacheException e) {
      // ignore set storage group concurrently
      node = mtree.getDeviceNodeWithAutoCreating(detachedDevice, sgLevel);
      return node;
    } finally {
      if (node != null) {
        node.readLock();
      }
      lock.writeLock().unlock();
    }
  }

  public MNode getDeviceMNodeWithAutoCreateAndReadLock(String path, List<String> detachedPath) throws MetadataException {
    return getDeviceMNodeWithAutoCreateAndReadLock(
        path, detachedPath, config.isAutoCreateSchemaEnabled(), config.getDefaultStorageGroupLevel());
  }

  public MNode getDeviceMNode(String path, List<String> detachedPath) throws MetadataException {
    lock.readLock().lock();
    MNode node;
    try {
      node = mNodeCache.get(path, detachedPath);
      return node;
    } catch (Exception e) {
      throw new PathNotExistException(path);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * To reduce the String number in memory, use the deviceId from MManager instead of the deviceId
   * read from disk
   *
   * @param path read from disk
   * @return deviceId
   */
  @TestOnly
  public String getDevice(String path) {
    MNode deviceNode = null;
    try {
      deviceNode = getDeviceMNode(path, MetaUtils.splitPathToDetachedPath(path));
      path = deviceNode.getFullPath();
    } catch (MetadataException | NullPointerException e) {
      // Cannot get deviceId from MManager, return the input deviceId
    }
    return path;
  }

  public MNode getChild(MNode parent, String child) {
    lock.readLock().lock();
    try {
      return parent.getChild(child);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get metadata in string
   */
  public String getMetadataInString() {
    lock.readLock().lock();
    try {
      return TIME_SERIES_TREE_HEADER + mtree.toString();
    } finally {
      lock.readLock().unlock();
    }
  }

  @TestOnly
  public void setMaxSeriesNumberAmongStorageGroup(long maxSeriesNumberAmongStorageGroup) {
    this.maxSeriesNumberAmongStorageGroup = maxSeriesNumberAmongStorageGroup;
  }

  public long getMaximalSeriesNumberAmongStorageGroups() {
    return maxSeriesNumberAmongStorageGroup;
  }

  public void setTTL(List<String> detachedStorageGroup, long dataTTL) throws MetadataException, IOException {
    lock.writeLock().lock();
    try {
      getStorageGroupMNode(detachedStorageGroup).setDataTTL(dataTTL);
      if (!isRecovering) {
        logWriter.setTTL(MetaUtils.concatDetachedPathByDot(detachedStorageGroup), dataTTL);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Check whether the given path contains a storage group change or set the new offset of a
   * timeseries
   *
   * @param detachedPath   timeseries
   * @param offset offset in the tag file
   */
  public void changeOffset(List<String> detachedPath, long offset) throws MetadataException {
    lock.writeLock().lock();
    try {
      ((MeasurementMNode) mtree.getMNodeByDetachedPath(detachedPath)).setOffset(offset);
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void changeAlias(List<String> detachedPath, String alias) throws MetadataException {
    lock.writeLock().lock();
    try {
      MeasurementMNode leafMNode = (MeasurementMNode) mtree.getMNodeByDetachedPath(detachedPath);
      if (leafMNode.getAlias() != null) {
        leafMNode.getParent().deleteAliasChild(leafMNode.getAlias());
      }
      leafMNode.getParent().addAlias(alias, leafMNode);
      leafMNode.setAlias(alias);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * upsert tags and attributes key-value for the timeseries if the key has existed, just use the
   * new value to update it.
   *
   * @param alias         newly added alias
   * @param tagsMap       newly added tags map
   * @param attributesMap newly added attributes map
   * @param detachedPath timeseries
   */
  public void upsertTagsAndAttributes(String alias, Map<String, String> tagsMap,
      Map<String, String> attributesMap, List<String> detachedPath) throws MetadataException, IOException {
    lock.writeLock().lock();
    try {
      MNode mNode = mtree.getMNodeByDetachedPath(detachedPath);
      if (!(mNode instanceof MeasurementMNode)) {
        throw new PathNotExistException(MetaUtils.concatDetachedPathByDot(detachedPath));
      }
      MeasurementMNode leafMNode = (MeasurementMNode) mNode;
      // upsert alias
      if (alias != null && !alias.equals(leafMNode.getAlias())) {

        if (leafMNode.getParent().hasChild(alias)) {
          throw new MetadataException("The alias already exists.");
        }
        if (leafMNode.getAlias() != null) {
          leafMNode.getParent().deleteAliasChild(leafMNode.getAlias());
        }
        leafMNode.getParent().addAlias(alias, leafMNode);
        leafMNode.setAlias(alias);
        // persist to WAL
        logWriter.changeAlias(MetaUtils.concatDetachedPathByDot(detachedPath), alias);
      }

      if (tagsMap == null && attributesMap == null) {
        return;
      }
      // no tag or attribute, we need to add a new record in log
      if (leafMNode.getOffset() < 0) {
        long offset = tagLogFile.write(tagsMap, attributesMap);
        logWriter.changeOffset(MetaUtils.concatDetachedPathByDot(detachedPath), offset);
        leafMNode.setOffset(offset);
        // update inverted Index map
        if (tagsMap != null) {
          for (Entry<String, String> entry : tagsMap.entrySet()) {
            tagIndex.computeIfAbsent(entry.getKey(), k -> new HashMap<>())
                .computeIfAbsent(entry.getValue(), v -> new HashSet<>()).add(leafMNode);
          }
        }
        return;
      }

      Pair<Map<String, String>, Map<String, String>> pair =
          tagLogFile.read(config.getTagAttributeTotalSize(), leafMNode.getOffset());

      if (tagsMap != null) {
        for (Entry<String, String> entry : tagsMap.entrySet()) {
          String key = entry.getKey();
          String value = entry.getValue();
          String beforeValue = pair.left.get(key);
          pair.left.put(key, value);
          // if the key has existed and the value is not equal to the new one
          // we should remove before key-value from inverted index map
          if (beforeValue != null && !beforeValue.equals(value)) {

            if (tagIndex.containsKey(key) && tagIndex.get(key).containsKey(beforeValue)) {
              if (logger.isDebugEnabled()) {
                logger.debug(String.format(
                    "Upsert: TimeSeries %s is removed from tag inverted index, "
                        + "tag key is %s, tag value is %s, tlog offset is %d",
                    leafMNode.getFullPath(), key, beforeValue, leafMNode.getOffset()));
              }

              tagIndex.get(key).get(beforeValue).remove(leafMNode);
              if (tagIndex.get(key).get(beforeValue).isEmpty()) {
                tagIndex.get(key).remove(beforeValue);
              }
            } else {
              if (logger.isDebugEnabled()) {
                logger.debug(String.format(
                    "Upsert: TimeSeries %s's tag info has been removed from tag inverted index "
                        + "before deleting it, tag key is %s, tag value is %s, tlog offset is %d, contains key %b",
                    leafMNode.getFullPath(), key, beforeValue, leafMNode.getOffset(),
                    tagIndex.containsKey(key)));
              }
            }
          }

          // if the key doesn't exist or the value is not equal to the new one
          // we should add a new key-value to inverted index map
          if (beforeValue == null || !beforeValue.equals(value)) {
            tagIndex.computeIfAbsent(key, k -> new HashMap<>())
                .computeIfAbsent(value, v -> new HashSet<>()).add(leafMNode);
          }
        }
      }

      pair.right.putAll(attributesMap);

      // persist the change to disk
      tagLogFile.write(pair.left, pair.right, leafMNode.getOffset());

    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * add new attributes key-value for the timeseries
   *
   * @param attributesMap newly added attributes map
   * @param detachedPath timeseries
   */
  public void addAttributes(Map<String, String> attributesMap, List<String> detachedPath)
      throws MetadataException, IOException {
    lock.writeLock().lock();
    try {
      MNode mNode = mtree.getMNodeByDetachedPath(detachedPath);
      if (!(mNode instanceof MeasurementMNode)) {
        throw new PathNotExistException(MetaUtils.concatDetachedPathByDot(detachedPath));
      }
      MeasurementMNode leafMNode = (MeasurementMNode) mNode;
      // no tag or attribute, we need to add a new record in log
      if (leafMNode.getOffset() < 0) {
        long offset = tagLogFile.write(Collections.emptyMap(), attributesMap);
        logWriter.changeOffset(MetaUtils.concatDetachedPathByDot(detachedPath), offset);
        leafMNode.setOffset(offset);
        return;
      }

      Pair<Map<String, String>, Map<String, String>> pair =
          tagLogFile.read(config.getTagAttributeTotalSize(), leafMNode.getOffset());

      for (Entry<String, String> entry : attributesMap.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        if (pair.right.containsKey(key)) {
          throw new MetadataException(
              String.format("TimeSeries [%s] already has the attribute [%s].", MetaUtils.concatDetachedPathByDot(detachedPath), key));
        }
        pair.right.put(key, value);
      }

      // persist the change to disk
      tagLogFile.write(pair.left, pair.right, leafMNode.getOffset());
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * add new tags key-value for the timeseries
   *
   * @param tagsMap newly added tags map
   * @param detachedPath timeseries
   */
  public void addTags(Map<String, String> tagsMap, List<String> detachedPath)
      throws MetadataException, IOException {
    lock.writeLock().lock();
    try {
      MNode mNode = mtree.getMNodeByDetachedPath(detachedPath);
      if (!(mNode instanceof MeasurementMNode)) {
        throw new PathNotExistException(MetaUtils.concatDetachedPathByDot(detachedPath));
      }
      MeasurementMNode leafMNode = (MeasurementMNode) mNode;
      // no tag or attribute, we need to add a new record in log
      if (leafMNode.getOffset() < 0) {
        long offset = tagLogFile.write(tagsMap, Collections.emptyMap());
        logWriter.changeOffset(MetaUtils.concatDetachedPathByDot(detachedPath), offset);
        leafMNode.setOffset(offset);
        // update inverted Index map
        for (Entry<String, String> entry : tagsMap.entrySet()) {
          tagIndex.computeIfAbsent(entry.getKey(), k -> new HashMap<>())
              .computeIfAbsent(entry.getValue(), v -> new HashSet<>()).add(leafMNode);
        }
        return;
      }

      Pair<Map<String, String>, Map<String, String>> pair =
          tagLogFile.read(config.getTagAttributeTotalSize(), leafMNode.getOffset());

      for (Entry<String, String> entry : tagsMap.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        if (pair.left.containsKey(key)) {
          throw new MetadataException(
              String.format("TimeSeries [%s] already has the tag [%s].", MetaUtils.concatDetachedPathByDot(detachedPath), key));
        }
        pair.left.put(key, value);
      }

      // persist the change to disk
      tagLogFile.write(pair.left, pair.right, leafMNode.getOffset());

      // update tag inverted map
      tagsMap.forEach((key, value) -> tagIndex.computeIfAbsent(key, k -> new HashMap<>())
          .computeIfAbsent(value, v -> new HashSet<>()).add(leafMNode));

    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * drop tags or attributes of the timeseries
   *
   * @param keySet tags key or attributes key
   * @param detachedPath timeseries path
   */
  public void dropTagsOrAttributes(Set<String> keySet, List<String> detachedPath)
      throws MetadataException, IOException {
    lock.writeLock().lock();
    try {
      MNode mNode = mtree.getMNodeByDetachedPath(detachedPath);
      if (!(mNode instanceof MeasurementMNode)) {
        throw new PathNotExistException(MetaUtils.concatDetachedPathByDot(detachedPath));
      }
      MeasurementMNode leafMNode = (MeasurementMNode) mNode;
      // no tag or attribute, just do nothing.
      if (leafMNode.getOffset() < 0) {
        return;
      }
      Pair<Map<String, String>, Map<String, String>> pair =
          tagLogFile.read(config.getTagAttributeTotalSize(), leafMNode.getOffset());

      Map<String, String> deleteTag = new HashMap<>();
      for (String key : keySet) {
        // check tag map
        // check attribute map
        if (pair.left.containsKey(key)) {
          deleteTag.put(key, pair.left.remove(key));
        } else {
          pair.right.remove(key);
        }
      }

      // persist the change to disk
      tagLogFile.write(pair.left, pair.right, leafMNode.getOffset());

      for (Entry<String, String> entry : deleteTag.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        // change the tag inverted index map
        if (tagIndex.containsKey(key) && tagIndex.get(key).containsKey(value)) {
          if (logger.isDebugEnabled()) {
            logger.debug(String.format(
                "Drop: TimeSeries %s is removed from tag inverted index, "
                    + "tag key is %s, tag value is %s, tlog offset is %d",
                leafMNode.getFullPath(), entry.getKey(), entry.getValue(), leafMNode.getOffset()));
          }

          tagIndex.get(key).get(value).remove(leafMNode);
          if (tagIndex.get(key).get(value).isEmpty()) {
            tagIndex.get(key).remove(value);
            if (tagIndex.get(key).isEmpty()) {
              tagIndex.remove(key);
            }
          }
        } else {
          if (logger.isDebugEnabled()) {
            logger.debug(String.format(
                "Drop: TimeSeries %s's tag info has been removed from tag inverted index "
                    + "before deleting it, tag key is %s, tag value is %s, tlog offset is %d, contains key %b",
                leafMNode.getFullPath(), key, value, leafMNode.getOffset(),
                tagIndex.containsKey(key)));
          }
        }

      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * set/change the values of tags or attributes
   *
   * @param alterMap the new tags or attributes key-value
   * @param detachedPath timeseries
   */
  public void setTagsOrAttributesValue(Map<String, String> alterMap, List<String> detachedPath)
      throws MetadataException, IOException {
    lock.writeLock().lock();
    try {
      MNode mNode = mtree.getMNodeByDetachedPath(detachedPath);
      if (!(mNode instanceof MeasurementMNode)) {
        throw new PathNotExistException(MetaUtils.concatDetachedPathByDot(detachedPath));
      }
      MeasurementMNode leafMNode = (MeasurementMNode) mNode;
      if (leafMNode.getOffset() < 0) {
        throw new MetadataException(
            String.format("TimeSeries [%s] does not have any tag/attribute.", MetaUtils.concatDetachedPathByDot(detachedPath)));
      }

      // tags, attributes
      Pair<Map<String, String>, Map<String, String>> pair =
          tagLogFile.read(config.getTagAttributeTotalSize(), leafMNode.getOffset());
      Map<String, String> oldTagValue = new HashMap<>();
      Map<String, String> newTagValue = new HashMap<>();

      for (Entry<String, String> entry : alterMap.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        // check tag map
        if (pair.left.containsKey(key)) {
          oldTagValue.put(key, pair.left.get(key));
          newTagValue.put(key, value);
          pair.left.put(key, value);
        } else if (pair.right.containsKey(key)) {
          // check attribute map
          pair.right.put(key, value);
        } else {
          throw new MetadataException(
              String.format("TimeSeries [%s] does not have tag/attribute [%s].", MetaUtils.concatDetachedPathByDot(detachedPath), key));
        }
      }

      // persist the change to disk
      tagLogFile.write(pair.left, pair.right, leafMNode.getOffset());

      for (Entry<String, String> entry : oldTagValue.entrySet()) {
        String key = entry.getKey();
        String beforeValue = entry.getValue();
        String currentValue = newTagValue.get(key);
        // change the tag inverted index map
        if (tagIndex.containsKey(key) && tagIndex.get(key).containsKey(beforeValue)) {

          if (logger.isDebugEnabled()) {
            logger.debug(String.format(
                "Set: TimeSeries %s is removed from tag inverted index, "
                    + "tag key is %s, tag value is %s, tlog offset is %d",
                leafMNode.getFullPath(), entry.getKey(), beforeValue, leafMNode.getOffset()));
          }

          tagIndex.get(key).get(beforeValue).remove(leafMNode);
        } else {
          if (logger.isDebugEnabled()) {
            logger.debug(String.format(
                "Set: TimeSeries %s's tag info has been removed from tag inverted index "
                    + "before deleting it, tag key is %s, tag value is %s, tlog offset is %d, contains key %b",
                leafMNode.getFullPath(), key, beforeValue, leafMNode.getOffset(),
                tagIndex.containsKey(key)));
          }
        }
        tagIndex.computeIfAbsent(key, k -> new HashMap<>())
            .computeIfAbsent(currentValue, k -> new HashSet<>()).add(leafMNode);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * rename the tag or attribute's key of the timeseries
   *
   * @param oldKey old key of tag or attribute
   * @param newKey new key of tag or attribute
   * @param detachedPath timeseries
   */
  public void renameTagOrAttributeKey(String oldKey, String newKey, List<String> detachedPath)
      throws MetadataException, IOException {
    lock.writeLock().lock();
    try {
      MNode mNode = mtree.getMNodeByDetachedPath(detachedPath);
      if (!(mNode instanceof MeasurementMNode)) {
        throw new PathNotExistException(MetaUtils.concatDetachedPathByDot(detachedPath));
      }
      MeasurementMNode leafMNode = (MeasurementMNode) mNode;
      if (leafMNode.getOffset() < 0) {
        throw new MetadataException(
            String.format("TimeSeries [%s] does not have [%s] tag/attribute.", MetaUtils.concatDetachedPathByDot(detachedPath), oldKey));
      }
      // tags, attributes
      Pair<Map<String, String>, Map<String, String>> pair =
          tagLogFile.read(config.getTagAttributeTotalSize(), leafMNode.getOffset());

      // current name has existed
      if (pair.left.containsKey(newKey) || pair.right.containsKey(newKey)) {
        throw new MetadataException(
            String.format(
                "TimeSeries [%s] already has a tag/attribute named [%s].", MetaUtils.concatDetachedPathByDot(detachedPath), newKey));
      }

      // check tag map
      if (pair.left.containsKey(oldKey)) {
        String value = pair.left.remove(oldKey);
        pair.left.put(newKey, value);
        // persist the change to disk
        tagLogFile.write(pair.left, pair.right, leafMNode.getOffset());
        // change the tag inverted index map
        if (tagIndex.containsKey(oldKey) && tagIndex.get(oldKey).containsKey(value)) {

          if (logger.isDebugEnabled()) {
            logger.debug(String.format(
                "Rename: TimeSeries %s is removed from tag inverted index, "
                    + "tag key is %s, tag value is %s, tlog offset is %d",
                leafMNode.getFullPath(), oldKey, value, leafMNode.getOffset()));
          }

          tagIndex.get(oldKey).get(value).remove(leafMNode);

        } else {
          if (logger.isDebugEnabled()) {
            logger.debug(String.format(
                "Rename: TimeSeries %s's tag info has been removed from tag inverted index "
                    + "before deleting it, tag key is %s, tag value is %s, tlog offset is %d, contains key %b",
                leafMNode.getFullPath(), oldKey, value, leafMNode.getOffset(),
                tagIndex.containsKey(oldKey)));
          }
        }
        tagIndex.computeIfAbsent(newKey, k -> new HashMap<>())
            .computeIfAbsent(value, k -> new HashSet<>()).add(leafMNode);
      } else if (pair.right.containsKey(oldKey)) {
        // check attribute map
        pair.right.put(newKey, pair.right.remove(oldKey));
        // persist the change to disk
        tagLogFile.write(pair.left, pair.right, leafMNode.getOffset());
      } else {
        throw new MetadataException(
            String.format("TimeSeries [%s] does not have tag/attribute [%s].", MetaUtils.concatDetachedPathByDot(detachedPath), oldKey));
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Check whether the given path contains a storage group
   */
  boolean checkStorageGroupByPath(List<String> detachedPath) {
    lock.readLock().lock();
    try {
      return mtree.checkStorageGroupByPath(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }


  /**
   * Get all storage groups under the detachedPath of given path
   *
   * @return List of String represented all storage group names
   * @apiNote :for cluster
   */
  List<String> getStorageGroupByPath(List<String> detachedPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getStorageGroupByDetachedPath(detachedPath);
    } catch (MetadataException e) {
      throw new MetadataException(e);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * get all storageGroups ttl
   *
   * @return key-> storageGroupName, value->ttl
   */
  public Map<String, Long> getStorageGroupsTTL() {
    Map<String, Long> storageGroupsTTL = new HashMap<>();
    try {
      List<String> storageGroups = this.getAllDetachedStorageGroups();
      for (String storageGroup : storageGroups) {
        long ttl = getStorageGroupMNode(MetaUtils.splitPathToDetachedPath(storageGroup)).getDataTTL();
        storageGroupsTTL.put(storageGroup, ttl);
      }
    } catch (MetadataException e) {
      logger.error("get storage groups ttl failed.", e);
    }
    return storageGroupsTTL;
  }

  public void collectTimeseriesSchema(MNode startingNode,
      Collection<TimeseriesSchema> timeseriesSchemas) {
    Deque<MNode> mNodeDeque = new ArrayDeque<>();
    mNodeDeque.addLast(startingNode);
    while (!mNodeDeque.isEmpty()) {
      MNode mNode = mNodeDeque.removeFirst();
      if (mNode instanceof MeasurementMNode) {
        MeasurementSchema mNodeSchema = ((MeasurementMNode) mNode).getSchema();
        timeseriesSchemas.add(new TimeseriesSchema(mNode.getFullPath(), mNodeSchema.getType(),
            mNodeSchema.getEncodingType(), mNodeSchema.getCompressor()));
      } else if (!mNode.getChildren().isEmpty()) {
        mNodeDeque.addAll(mNode.getChildren().values());
      }
    }
  }

  public void collectMeasurementSchema(MNode startingNode,
      Collection<MeasurementSchema> timeseriesSchemas) {
    Deque<MNode> mNodeDeque = new ArrayDeque<>();
    mNodeDeque.addLast(startingNode);
    while (!mNodeDeque.isEmpty()) {
      MNode mNode = mNodeDeque.removeFirst();
      if (mNode instanceof MeasurementMNode) {
        MeasurementSchema mNodeSchema = ((MeasurementMNode) mNode).getSchema();
        timeseriesSchemas.add(new MeasurementSchema(mNode.getFullPath(), mNodeSchema.getType(),
            mNodeSchema.getEncodingType(), mNodeSchema.getCompressor()));
      } else if (!mNode.getChildren().isEmpty()) {
        mNodeDeque.addAll(mNode.getChildren().values());
      }
    }
  }

  /**
   * Collect the timeseries schemas under "startingPath".
   *
   * @param startingPathNodes
   * @param measurementSchemas
   */
  public void collectSeries(List<String> startingPathNodes, List<MeasurementSchema> measurementSchemas) {
    MNode mNode;
    try {
      mNode = getMNodeByDetachedPath(startingPathNodes);
    } catch (MetadataException e) {
      return;
    }
    collectMeasurementSchema(mNode, measurementSchemas);
  }

  /**
   * For a path, infer all storage groups it may belong to. The path can have wildcards.
   *
   * <p>Consider the path into two parts: (1) the sub path which can not contain a storage group
   * name and (2) the sub path which is substring that begin after the storage group name.
   *
   * <p>(1) Suppose the part of the path can not contain a storage group name (e.g.,
   * "root".contains("root.sg") == false), then: If the wildcard is not at the tail, then for each
   * wildcard, only one level will be inferred and the wildcard will be removed. If the wildcard is
   * at the tail, then the inference will go on until the storage groups are found and the wildcard
   * will be kept. (2) Suppose the part of the path is a substring that begin after the storage
   * group name. (e.g., For "root.*.sg1.a.*.b.*" and "root.x.sg1" is a storage group, then this part
   * is "a.*.b.*"). For this part, keep what it is.
   *
   * <p>Assuming we have three SGs: root.group1, root.group2, root.area1.group3 Eg1: for input
   * "root.*", returns ("root.group1", "root.group1.*"), ("root.group2", "root.group2.*")
   * ("root.area1.group3", "root.area1.group3.*") Eg2: for input "root.*.s1", returns
   * ("root.group1", "root.group1.s1"), ("root.group2", "root.group2.s1")
   *
   * <p>Eg3: for input "root.area1.*", returns ("root.area1.group3", "root.area1.group3.*")
   *
   * @param detachedPath of path can be a prefix or a full path.
   * @return StorageGroupName-FullPath pairs
   */
  public Map<String, String> determineStorageGroup(List<String> detachedPath) throws IllegalPathException {
    lock.readLock().lock();
    try {
      return mtree.determineStorageGroup(detachedPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * StorageGroupFilter filters unsatisfied storage groups in metadata queries to speed up and
   * deduplicate.
   */
  @FunctionalInterface
  public interface StorageGroupFilter {

    boolean satisfy(String storageGroup);
  }

  /**
   * if the path is in local mtree, nothing needed to do (because mtree is in the memory); Otherwise
   * cache the path to mRemoteSchemaCache
   */
  public void cacheMeta(String path, MeasurementMeta meta) {
    // do nothing
  }

  public void updateLastCache(List<String> detachedPath, TimeValuePair timeValuePair,
                              boolean highPriorityUpdate, Long latestFlushedTime,
                              MeasurementMNode mNode) {
    if (mNode != null) {
      mNode.updateCachedLast(timeValuePair, highPriorityUpdate, latestFlushedTime);
    } else {
      try {
        MeasurementMNode mNode1 = (MeasurementMNode) mtree.getMNodeByDetachedPath(detachedPath);
        mNode1.updateCachedLast(timeValuePair, highPriorityUpdate, latestFlushedTime);
      } catch (MetadataException e) {
        logger.warn("failed to update last cache for the {}, err:{}", MetaUtils.concatDetachedPathByDot(detachedPath), e.getMessage());
      }
    }
  }


  public TimeValuePair getLastCache(List<String> detachedPath) {
    try {
      MeasurementMNode mNode = (MeasurementMNode) mtree.getMNodeByDetachedPath(detachedPath);
      return mNode.getCachedLast();
    } catch (MetadataException e) {
      logger.warn("failed to get last cache for the {}, err:{}", MetaUtils.concatDetachedPathByDot(detachedPath), e.getMessage());
    }
    return null;
  }

  private void checkMTreeModified() {
    if (logWriter == null || logFile == null) {
      // the logWriter is not initialized now, we skip the check once.
      return;
    }
    if (System.currentTimeMillis() - logFile.lastModified() < mtreeSnapshotThresholdTime) {
      if (logger.isDebugEnabled()) {
        logger.debug("MTree snapshot need not be created. Time from last modification: {} ms.",
            System.currentTimeMillis() - logFile.lastModified());
      }
    } else if (logWriter.getLineNumber() < mtreeSnapshotInterval) {
      if (logger.isDebugEnabled()) {
        logger.debug("MTree snapshot need not be created. New mlog line number: {}.",
            logWriter.getLineNumber());
      }
    } else {
      logger.info("New mlog line number: {}, time from last modification: {} ms",
          logWriter.getLineNumber(), System.currentTimeMillis() - logFile.lastModified());
      createMTreeSnapshot();
    }
  }

  public void createMTreeSnapshot() {
    lock.readLock().lock();
    long time = System.currentTimeMillis();
    logger.info("Start creating MTree snapshot to {}", mtreeSnapshotPath);
    try {
      mtree.serializeTo(mtreeSnapshotTmpPath);
      File tmpFile = SystemFileFactory.INSTANCE.getFile(mtreeSnapshotTmpPath);
      File snapshotFile = SystemFileFactory.INSTANCE.getFile(mtreeSnapshotPath);
      if (snapshotFile.exists()) {
        Files.delete(snapshotFile.toPath());
      }
      if (tmpFile.renameTo(snapshotFile)) {
        logger.info("Finish creating MTree snapshot to {}, spend {} ms.", mtreeSnapshotPath,
            System.currentTimeMillis() - time);
      }
      logWriter.clear();
    } catch (IOException e) {
      logger.warn("Failed to create MTree snapshot to {}", mtreeSnapshotPath, e);
      if (SystemFileFactory.INSTANCE.getFile(mtreeSnapshotTmpPath).exists()) {
        try {
          Files.delete(SystemFileFactory.INSTANCE.getFile(mtreeSnapshotTmpPath).toPath());
        } catch (IOException e1) {
          logger.warn("delete file {} failed: {}", mtreeSnapshotTmpPath, e1.getMessage());
        }
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * get schema for device. Attention!!!  Only support insertPlan
   *
   * @throws MetadataException
   */
  public MeasurementSchema[] getSeriesSchemasAndReadLockDevice(String deviceId, List<String> detachedPath,
      String[] measurementList, InsertPlan plan) throws MetadataException {
    MeasurementSchema[] schemas = new MeasurementSchema[measurementList.length];

    MNode deviceMNode;
    // 1. get device node
    deviceMNode = getDeviceMNodeWithAutoCreateAndReadLock(deviceId, detachedPath);

    // 2. get schema of each measurement
    for (int i = 0; i < measurementList.length; i++) {
      try {
        // if do not has measurement
        if (!deviceMNode.hasChild(measurementList[i])) {
          // could not create it
          if (!config.isAutoCreateSchemaEnabled()) {
            throw new MetadataException(String.format(
                "Current deviceId[%s] does not contain measurement:%s", deviceId,
                measurementList[i]));
          }

          TSDataType dataType = getTypeInLoc(plan, i);
          detachedPath.add(measurementList[i]);
          createTimeseries(detachedPath,
            dataType,
            getDefaultEncoding(dataType),
            TSFileDescriptor.getInstance().getConfig().getCompressor(),
            Collections.emptyMap());
          detachedPath.remove(detachedPath.size() - 1);
        }

        MeasurementMNode measurementMNode = (MeasurementMNode) getChild(deviceMNode,
            measurementList[i]);

        // check type is match
        TSDataType insertDataType = null;
        if (plan instanceof InsertRowPlan) {
          if (!((InsertRowPlan) plan).isNeedInferType()) {
            // only when InsertRowPlan's values is object[], we should check type
            insertDataType = getTypeInLoc(plan, i);
          } else {
            insertDataType = measurementMNode.getSchema().getType();
          }
        } else if (plan instanceof InsertTabletPlan) {
          insertDataType = getTypeInLoc(plan, i);
        }

        if (measurementMNode.getSchema().getType() != insertDataType) {
          logger.warn("DataType mismatch, Insert measurement {} type {}, metadata tree type {}",
              measurementList[i], insertDataType, measurementMNode.getSchema().getType());
          if (!config.isEnablePartialInsert()) {
            throw new MetadataException(String.format(
                "DataType mismatch, Insert measurement %s type %s, metadata tree type %s",
                measurementList[i], insertDataType, measurementMNode.getSchema().getType()));
          } else {
            // mark failed measurement
            plan.markFailedMeasurementInsertion(i);
            continue;
          }
        }

        schemas[i] = measurementMNode.getSchema();
        if (schemas[i] != null) {
          measurementList[i] = schemas[i].getMeasurementId();
        }
      } catch (MetadataException e) {
        logger.warn("meet error when check {}.{}, message: {}", deviceId, measurementList[i],
            e.getMessage());
        if (config.isEnablePartialInsert()) {
          // mark failed measurement
          plan.markFailedMeasurementInsertion(i);
        } else {
          throw e;
        }
      }
    }

    plan.setDeviceMNode(deviceMNode);

    return schemas;
  }

  /**
   * Get default encoding by dataType
   */
  private TSEncoding getDefaultEncoding(TSDataType dataType) {
    IoTDBConfig conf = IoTDBDescriptor.getInstance().getConfig();
    switch (dataType) {
      case BOOLEAN:
        return conf.getDefaultBooleanEncoding();
      case INT32:
        return conf.getDefaultInt32Encoding();
      case INT64:
        return conf.getDefaultInt64Encoding();
      case FLOAT:
        return conf.getDefaultFloatEncoding();
      case DOUBLE:
        return conf.getDefaultDoubleEncoding();
      case TEXT:
        return conf.getDefaultTextEncoding();
      default:
        throw new UnSupportedDataTypeException(
            String.format("Data type %s is not supported.", dataType.toString()));
    }
  }

  /**
   * get dataType of plan, in loc measurements only support InsertRowPlan and InsertTabletPlan
   *
   * @throws MetadataException
   */
  private TSDataType getTypeInLoc(InsertPlan plan, int loc) throws MetadataException {
    TSDataType dataType;
    if (plan instanceof InsertRowPlan) {
      InsertRowPlan tPlan = (InsertRowPlan) plan;
      dataType = TypeInferenceUtils
          .getPredictedDataType(tPlan.getValues()[loc], tPlan.isNeedInferType());
    } else if (plan instanceof InsertTabletPlan) {
      dataType = (plan).getDataTypes()[loc];
    } else {
      throw new MetadataException(String.format(
          "Only support insert and insertTablet, plan is [%s]", plan.getOperatorType()));
    }
    return dataType;
  }

  /**
   * when insert, we lock device node for not create deleted time series after insert, we should
   * call this function to unlock the device node
   *
   * @param deviceId
   */
  public void unlockDeviceReadLock(String deviceId, List<String> detachedDevice) {
    try {
      MNode mNode = getDeviceMNode(deviceId, detachedDevice);
      mNode.readUnlock();
    } catch (MetadataException e) {
      // ignore the exception
    }
  }
}
