package com.alibaba.datax.plugin.reader.s3readerparquet;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordSender;
import com.alibaba.datax.common.spi.Reader;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.reader.hdfsreader.HdfsReader;
import com.alibaba.datax.plugin.reader.ossreader.Key;
import com.alibaba.datax.plugin.reader.ossreader.OssReaderErrorCode;
import com.alibaba.datax.plugin.reader.ossreader.util.HdfsParquetUtil;
import com.alibaba.datax.plugin.reader.ossreader.util.OssUtil;
import com.alibaba.datax.plugin.unstructuredstorage.reader.UnstructuredStorageReaderUtil;
import com.alibaba.datax.plugin.unstructuredstorage.reader.split.StartEndPair;
import com.aliyun.oss.OSSClient;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class S3ReaderParquet extends Reader {
    public static class Job extends Reader.Job {
        private static final Logger LOG = LoggerFactory
                .getLogger(S3ReaderParquet.Job.class);

        private Configuration readerOriginConfig = null;

        private OSSClient ossClient = null;
        private String endpoint;
        private String accessId;
        private String accessKey;
        private String bucket;
        private boolean successOnNoObject;
        private Boolean isBinaryFile;

        private List<String> objects;
        private List<Pair<String, Long>> objectSizePairs; /*用于任务切分的依据*/

        private String fileFormat;

        private HdfsReader.Job hdfsReaderJob;
        private boolean useHdfsReaderProxy = false;

        @Override
        public void init() {
            LOG.debug("init() begin...");
            this.readerOriginConfig = this.getPluginJobConf();
            this.basicValidateParameter();
            this.fileFormat = this.readerOriginConfig.getString(com.alibaba.datax.plugin.unstructuredstorage.reader.Key.FILE_FORMAT,
                    com.alibaba.datax.plugin.unstructuredstorage.reader.Constant.DEFAULT_FILE_FORMAT);
            this.useHdfsReaderProxy = HdfsParquetUtil.isUseHdfsWriterProxy(this.fileFormat);
            if (useHdfsReaderProxy) {
                HdfsParquetUtil.adaptConfigurationFromS3(this.readerOriginConfig);
                this.hdfsReaderJob = new HdfsReader.Job();
                this.hdfsReaderJob.setJobPluginCollector(this.getJobPluginCollector());
                this.hdfsReaderJob.setPeerPluginJobConf(this.getPeerPluginJobConf());
                this.hdfsReaderJob.setPeerPluginName(this.getPeerPluginName());
                this.hdfsReaderJob.setPluginJobConf(this.getPluginJobConf());
                this.hdfsReaderJob.init();
                return;
            }
            LOG.debug("init() ok and end...");
        }


        private void basicValidateParameter() {
            endpoint = this.readerOriginConfig.getString(Key.ENDPOINT);
            if (StringUtils.isBlank(endpoint)) {
                throw DataXException.asDataXException(
                        OssReaderErrorCode.CONFIG_INVALID_EXCEPTION, "invalid endpoint");
            }

            accessId = this.readerOriginConfig.getString(Key.ACCESSID);
            if (StringUtils.isBlank(accessId)) {
                throw DataXException.asDataXException(
                        OssReaderErrorCode.CONFIG_INVALID_EXCEPTION,
                        "invalid accessId");
            }

            accessKey = this.readerOriginConfig.getString(Key.ACCESSKEY);
            if (StringUtils.isBlank(accessKey)) {
                throw DataXException.asDataXException(
                        OssReaderErrorCode.CONFIG_INVALID_EXCEPTION,
                        "invalid accessKey");
            }
        }

        // warn: 提前验证endpoint,accessId,accessKey,bucket,object的有效性
        private void validate() {
            // fxxk
            // ossClient = new OSSClient(endpoint,accessId,accessKey);
            ossClient = OssUtil.initOssClient(this.readerOriginConfig);


            bucket = this.readerOriginConfig.getString(Key.BUCKET);
            if (StringUtils.isBlank(bucket)) {
                throw DataXException.asDataXException(
                        OssReaderErrorCode.CONFIG_INVALID_EXCEPTION,
                        "invalid bucket");
            } else if (!ossClient.doesBucketExist(bucket)) {
                throw DataXException.asDataXException(
                        OssReaderErrorCode.CONFIG_INVALID_EXCEPTION,
                        "invalid bucket");
            }

            String object = this.readerOriginConfig.getString(Key.OBJECT);
            if (StringUtils.isBlank(object)) {
                throw DataXException.asDataXException(
                        OssReaderErrorCode.CONFIG_INVALID_EXCEPTION,
                        "invalid object");
            }

            if (this.isBinaryFile) {
                return;
            }
            UnstructuredStorageReaderUtil.validateParameter(this.readerOriginConfig);
        }


        @Override
        public void prepare() {
            if (useHdfsReaderProxy) {
                this.hdfsReaderJob.prepare();
                return;
            }
        }

        @Override
        public void post() {
            if (useHdfsReaderProxy) {
                this.hdfsReaderJob.post();
                return;
            }
            LOG.debug("post()");
        }

        @Override
        public void destroy() {
            if (useHdfsReaderProxy) {
                this.hdfsReaderJob.destroy();
                return;
            }
            LOG.debug("destroy()");
        }

        @Override
        public List<Configuration> split(int adviceNumber) {
            LOG.debug("split() begin...");
            if (useHdfsReaderProxy) {
                return hdfsReaderJob.split(adviceNumber);
            }
            return null;
        }
    }
    public static class Task extends Reader.Task {
        private static Logger LOG = LoggerFactory.getLogger(Reader.Task.class);

        private Configuration readerSliceConfig;
        private Boolean isBinaryFile;
        private Integer blockSizeInByte;
        private List<StartEndPair> allWorksForTask;
        private boolean originSkipHeader;
        private OSSClient ossClient;
        private String fileFormat;
        private HdfsReader.Task hdfsReaderTask;
        private boolean useHdfsReaderProxy = false;

        @Override
        public void init() {
            this.readerSliceConfig = this.getPluginJobConf();
            this.fileFormat = this.readerSliceConfig.getString(com.alibaba.datax.plugin.unstructuredstorage.reader.Key.FILE_FORMAT,
                    com.alibaba.datax.plugin.unstructuredstorage.reader.Constant.DEFAULT_FILE_FORMAT);
            this.useHdfsReaderProxy = HdfsParquetUtil.isUseHdfsWriterProxy(this.fileFormat);
            if (useHdfsReaderProxy) {
                this.hdfsReaderTask = new HdfsReader.Task();
                this.hdfsReaderTask.setPeerPluginJobConf(this.getPeerPluginJobConf());
                this.hdfsReaderTask.setPeerPluginName(this.getPeerPluginName());
                this.hdfsReaderTask.setPluginJobConf(this.getPluginJobConf());
                this.hdfsReaderTask.setReaderPluginSplitConf(this.getReaderPluginSplitConf());
                this.hdfsReaderTask.setTaskGroupId(this.getTaskGroupId());
                this.hdfsReaderTask.setTaskId(this.getTaskId());
                this.hdfsReaderTask.setTaskPluginCollector(this.getTaskPluginCollector());
                this.hdfsReaderTask.init();
                return;
            }
        }

        @Override
        public void prepare() {
            LOG.info("task prepare() begin...");
            if (useHdfsReaderProxy) {
                this.hdfsReaderTask.prepare();
                return;
            }
        }


        @Override
        public void startRead(RecordSender recordSender) {
            if (useHdfsReaderProxy) {
                this.hdfsReaderTask.startRead(recordSender);
                return;
            }
            boolean successOnNoObject = this.readerSliceConfig.getBool(Key.SUCCESS_ON_NO_Object, false);
            if (this.allWorksForTask.isEmpty() && successOnNoObject) {
                recordSender.flush();
                return;
            }
        }

        @Override
        public void post() {
            LOG.info("task post() begin...");
            if (useHdfsReaderProxy) {
                this.hdfsReaderTask.post();
                return;
            }
        }

        @Override
        public void destroy() {
            if (useHdfsReaderProxy) {
                this.hdfsReaderTask.destroy();
                return;
            }
            try {
                // this.ossClient.shutdown();
            } catch (Exception e) {
                LOG.warn("shutdown ossclient meet a exception:" + e.getMessage(), e);
            }
        }
    }
}
