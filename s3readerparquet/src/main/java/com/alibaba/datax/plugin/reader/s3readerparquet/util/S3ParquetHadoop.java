package com.alibaba.datax.plugin.reader.s3readerparquet.util;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;

import java.io.IOException;

public class S3ParquetHadoop {

    public static  void main(String[] args) {
        Configuration conf = new Configuration();
        conf.set("fs.s3a.access.key",  "7E1PYC4109UE02QEE3JB");
        conf.set("fs.s3a.secret.key",  "Z7R2tiqfU4e4mkFkRTWxNnBYzEFDgmfhK53L8lLW");
        // 如果使用 MinIO、Ceph 等 S3 兼容服务，则加 endpoint
        conf.set("fs.s3a.endpoint", "http://10.19.61.41:8060");
        Path path = new Path("s3a://dorisosstest-bt1/dorisdev/data/dw_dim_isc_hac_mes_org_dd_v2/result_975bb7a281db4a62-b56c753ae8b37b3c_0.parquet");
        try (ParquetReader<Group> reader =
                     ParquetReader.builder(new GroupReadSupport(), path)
                             .withConf(conf)
                             .build()) {

            Group record;
            while ((record = reader.read()) != null) {
                System.out.println(record); // 或自行解析 Group
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
