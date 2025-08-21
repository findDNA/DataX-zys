package com.alibaba.datax.plugin.reader.ossreader.util;

import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.reader.ossreader.Key;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * @Author: guxuan
 * @Date 2022-05-17 15:46
 */
public class HdfsParquetUtil {
    public static boolean isUseHdfsWriterProxy( String fileFormat){
        if("orc".equalsIgnoreCase(fileFormat) || "parquet".equalsIgnoreCase(fileFormat)){
            return true;
        }
        return false;
    }

    /**
     * 配置readerOriginConfig 适配hdfsreader读取oss parquet
     * https://help.aliyun.com/knowledge_detail/74344.html
     * @param readerOriginConfig
     */
    public static void adaptConfiguration(Configuration readerOriginConfig){
        String bucket = readerOriginConfig.getString(Key.BUCKET);
        String fs =String.format("oss://%s",bucket);
        readerOriginConfig.set(com.alibaba.datax.plugin.reader.hdfsreader.Key.DEFAULT_FS,fs);
        readerOriginConfig.set(com.alibaba.datax.plugin.reader.hdfsreader.Key.FILETYPE,
                readerOriginConfig.getString(com.alibaba.datax.plugin.unstructuredstorage.writer.Key.FILE_FORMAT));
        /**
         *  "path"、 "column" 相互一致
         */
        JSONObject hadoopConfig = new JSONObject();
        hadoopConfig.put(Key.FS_OSS_ACCESSID,readerOriginConfig.getString(Key.ACCESSID));
        hadoopConfig.put(Key.FS_OSS_ACCESSKEY,readerOriginConfig.getString(Key.ACCESSKEY));
        hadoopConfig.put(Key.FS_OSS_ENDPOINT,readerOriginConfig.getString(Key.ENDPOINT));
        readerOriginConfig.set(Key.HDOOP_CONFIG,Configuration.from(JSON.toJSONString(hadoopConfig)));
    }

    /**
     * 配置readerOriginConfig 适配hdfsreader读取oss parquet
     * https://help.aliyun.com/knowledge_detail/74344.html
     * @param readerOriginConfig
     */
    public static void adaptConfigurationFromS3(Configuration readerOriginConfig){
        String bucket = readerOriginConfig.getString(Key.BUCKET);
        String fs =String.format("s3a://%s",bucket);
        readerOriginConfig.set(com.alibaba.datax.plugin.reader.hdfsreader.Key.DEFAULT_FS,fs);
        readerOriginConfig.set(com.alibaba.datax.plugin.reader.hdfsreader.Key.FILETYPE,
                readerOriginConfig.getString(com.alibaba.datax.plugin.unstructuredstorage.writer.Key.FILE_FORMAT));
        /**
         *  "path"、 "column" 相互一致
         */
        JSONObject hadoopConfig = new JSONObject();
        hadoopConfig.put("fs.s3a.access.key",  readerOriginConfig.getString(Key.ACCESSID));
        hadoopConfig.put("fs.s3a.secret.key",  readerOriginConfig.getString(Key.ACCESSKEY));
        hadoopConfig.put("fs.s3a.endpoint",    readerOriginConfig.getString(Key.ENDPOINT));
        hadoopConfig.put("fs.s3a.path.style.access", "true");// 4. 如果使用裸 IP 或 MinIO，需要 path-style 访问
        //readerOriginConfig.set(Key.HDOOP_CONFIG,Configuration.from(JSON.toJSONString(hadoopConfig)));
    }
}
