package com.alibaba.datax.plugin.reader.s3reader;


import com.alibaba.datax.common.exception.DataXException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import com.alibaba.datax.common.util.Configuration;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static com.alibaba.datax.plugin.unstructuredstorage.reader.UnstructuredStorageReaderErrorCode.ILLEGAL_VALUE;


public class S3Util
{

    public static void main(String[] args) {
        Map<String, Object> map = new HashMap<>();
        map.put(S3Key.ENDPOINT, "http://x.x.x.x:9383");
        map.put(S3Key.ACCESS_ID, "admin");
        map.put(S3Key.ACCESS_KEY, "123456");
        Configuration conf = Configuration.from(map);
        initS3Client(conf);

    }
    public static S3Client initS3Client(Configuration conf) {
        String regionStr = conf.getString(S3Key.REGION);
        Region region = Region.of(regionStr);
        String accessId = conf.getString(S3Key.ACCESS_ID);
        String accessKey = conf.getString(S3Key.ACCESS_KEY);
        boolean pathStyleAccessEnabled = conf.getBool(S3Key.PATH_STYLE_ACCESS_ENABLED, false);

        try {
            AwsBasicCredentials awsCreds = AwsBasicCredentials.create(accessId, accessKey);
            return S3Client.builder()
                    .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                    .region(region)
                    .endpointOverride(URI.create(conf.getString(S3Key.ENDPOINT)))
                    .forcePathStyle(pathStyleAccessEnabled)
                    .build();
        } catch (IllegalArgumentException e) {
            throw DataXException.asDataXException(
                    ILLEGAL_VALUE, e.getMessage());
        }
    }
}
