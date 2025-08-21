package com.alibaba.datax.plugin.reader.s3readerparquet.util;

import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.plugin.reader.hdfsreader.DFSUtil;
import com.alibaba.datax.plugin.reader.hdfsreader.HdfsReaderErrorCode;
import com.alibaba.datax.plugin.reader.hdfsreader.ParquetMessageHelper;
import com.alibaba.datax.plugin.reader.hdfsreader.ParquetMeta;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class S3ParquetHadoop {

    public static  void main(String[] args) {
        Configuration conf = new Configuration();
        conf.set("fs.s3a.access.key",  "7E1PYC4109UE02QEE3JB");
        conf.set("fs.s3a.secret.key",  "Z7R2tiqfU4e4mkFkRTWxNnBYzEFDgmfhK53L8lLW");
        // 如果使用 MinIO、Ceph 等 S3 兼容服务，则加 endpoint
        conf.set("fs.s3a.endpoint", "http://10.19.61.41:8060");
        Path path = new Path("s3a://dorisosstest-bt1/dorisdev/data/dw_dim_isc_hac_mes_org_dd_v2/result_975bb7a281db4a62-b56c753ae8b37b3c_0.parquet");
        String schemaString = getParquetSchema(path.toString(), conf);
        System.out.println("schema:"+schemaString);
        MessageType parquetSchema = null;
        List<Type> parquetTypes = null;
        Map<String, ParquetMeta> parquetMetaMap = null;
        int fieldCount = 0;
        try {
            parquetSchema = MessageTypeParser.parseMessageType(schemaString);
            fieldCount = parquetSchema.getFieldCount();
            parquetTypes = parquetSchema.getFields();
            parquetMetaMap = ParquetMessageHelper.parseParquetTypes(parquetTypes);
        } catch (Exception e) {
            String message = String.format("Error parsing to MessageType via Schema string [%s]", schemaString);
           // LOG.error(message);
            throw DataXException.asDataXException(HdfsReaderErrorCode.PARSE_MESSAGE_TYPE_FROM_SCHEMA_ERROR, e);
        }

        try (ParquetReader<Group> reader =
                     ParquetReader.builder(new GroupReadSupport(), path)
                             .withConf(conf)
                             .build()) {

            Group record;
            while ((record = reader.read()) != null) {
                List<Object> formattedRecord = new ArrayList<Object>(fieldCount);
                for (int j = 0; j < fieldCount; j++) {
                    Object data = readFields(record, parquetTypes.get(j), j, parquetMetaMap, false);
                    formattedRecord.add(data);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private static String getParquetSchema(String sourceParquetFilePath, org.apache.hadoop.conf.Configuration hadoopConf) {
        GroupReadSupport readSupport = new GroupReadSupport();
        ParquetReader.Builder parquetReaderBuilder = ParquetReader.builder(readSupport, new Path(sourceParquetFilePath));
        ParquetReader<Group> reader = null;
        try {
            parquetReaderBuilder.withConf(hadoopConf);
            reader = parquetReaderBuilder.build();
            Group g = null;
            if ((g = reader.read()) != null) {
                return g.getType().toString();
            }
        } catch (Throwable e) {
           e.printStackTrace();
        } finally {
            org.apache.commons.io.IOUtils.closeQuietly(reader);
        }
        return null;
    }

    private static Object readFields(Group g, org.apache.parquet.schema.Type type, int index, Map<String, ParquetMeta> parquetMetaMap, boolean isUtcTimestamp) {
        if (getOriginalType(type, parquetMetaMap) == org.apache.parquet.schema.OriginalType.MAP) {
            Group groupData = g.getGroup(index, 0);
            List<org.apache.parquet.schema.Type> parquetTypes = groupData.getType().getFields();
            JSONObject data = new JSONObject();
            for (int i = 0; i < parquetTypes.size(); i++) {
                int j = groupData.getFieldRepetitionCount(i);
                // map key value 的对数
                for (int k = 0; k < j; k++) {
                    Group groupDataK = groupData.getGroup(0, k);
                    List<org.apache.parquet.schema.Type> parquetTypesK = groupDataK.getType().getFields();
                    if (2 != parquetTypesK.size()) {
                        // warn: 不是key value成对出现
                        throw new RuntimeException(String.format("bad parquet map type: %s", groupData.getValueToString(index, 0)));
                    }
                    Object subDataKey = readFields(groupDataK, parquetTypesK.get(0), 0, parquetMetaMap, isUtcTimestamp);
                    Object subDataValue = readFields(groupDataK, parquetTypesK.get(1), 1, parquetMetaMap, isUtcTimestamp);
                    if (StringUtils.equalsIgnoreCase("key", parquetTypesK.get(0).getName())) {
                        ((JSONObject) data).put(subDataKey.toString(), subDataValue);
                    } else {
                        ((JSONObject) data).put(subDataValue.toString(), subDataKey);
                    }
                }
            }
            return data;
        } else if (getOriginalType(type, parquetMetaMap) == org.apache.parquet.schema.OriginalType.MAP_KEY_VALUE) {
            Group groupData = g.getGroup(index, 0);
            List<org.apache.parquet.schema.Type> parquetTypes = groupData.getType().getFields();
            JSONObject data = new JSONObject();
            for (int i = 0; i < parquetTypes.size(); i++) {
                int j = groupData.getFieldRepetitionCount(i);
                // map key value 的对数
                for (int k = 0; k < j; k++) {
                    Group groupDataK = groupData.getGroup(0, k);
                    List<org.apache.parquet.schema.Type> parquetTypesK = groupDataK.getType().getFields();
                    if (2 != parquetTypesK.size()) {
                        // warn: 不是key value成对出现
                        throw new RuntimeException(String.format("bad parquet map type: %s", groupData.getValueToString(index, 0)));
                    }
                    Object subDataKey = readFields(groupDataK, parquetTypesK.get(0), 0, parquetMetaMap, isUtcTimestamp);
                    Object subDataValue = readFields(groupDataK, parquetTypesK.get(1), 1, parquetMetaMap, isUtcTimestamp);
                    if (StringUtils.equalsIgnoreCase("key", parquetTypesK.get(0).getName())) {
                        ((JSONObject) data).put(subDataKey.toString(), subDataValue);
                    } else {
                        ((JSONObject) data).put(subDataValue.toString(), subDataKey);
                    }
                }
            }
            return data;
        } else if (getOriginalType(type, parquetMetaMap) == org.apache.parquet.schema.OriginalType.LIST) {
            Group groupData = g.getGroup(index, 0);
            List<org.apache.parquet.schema.Type> parquetTypes = groupData.getType().getFields();
            JSONArray data = new JSONArray();
            for (int i = 0; i < parquetTypes.size(); i++) {
                Object subData = readFields(groupData, parquetTypes.get(i), i, parquetMetaMap, isUtcTimestamp);
                data.add(subData);
            }
            return data;
        } else if (getOriginalType(type, parquetMetaMap) == org.apache.parquet.schema.OriginalType.DECIMAL) {
            Binary binaryDate = g.getBinary(index, 0);
            if (null == binaryDate) {
                return null;
            } else {
                org.apache.hadoop.hive.serde2.io.HiveDecimalWritable decimalWritable = new org.apache.hadoop.hive.serde2.io.HiveDecimalWritable(binaryDate.getBytes(), asPrimitiveType(type, parquetMetaMap).getDecimalMetadata().getScale());
                // g.getType().getFields().get(1).asPrimitiveType().getDecimalMetadata().getScale()
                HiveDecimal hiveDecimal = decimalWritable.getHiveDecimal();
                if (null == hiveDecimal) {
                    return null;
                } else {
                    return hiveDecimal.bigDecimalValue();
                }
                // return decimalWritable.doubleValue();
            }
        } else if (getOriginalType(type, parquetMetaMap) == org.apache.parquet.schema.OriginalType.DATE) {
            return java.sql.Date.valueOf(LocalDate.ofEpochDay(g.getInteger(index, 0)));
        } else if (getOriginalType(type, parquetMetaMap) == org.apache.parquet.schema.OriginalType.UTF8) {
            return g.getValueToString(index, 0);
        } else {
            if (type.isPrimitive()) {
                PrimitiveType.PrimitiveTypeName primitiveTypeName = asPrimitiveType(type, parquetMetaMap).getPrimitiveTypeName();
                if (PrimitiveType.PrimitiveTypeName.BINARY == primitiveTypeName) {
                    return g.getValueToString(index, 0);
                } else if (PrimitiveType.PrimitiveTypeName.BOOLEAN == primitiveTypeName) {
                    return g.getValueToString(index, 0);
                } else if (PrimitiveType.PrimitiveTypeName.DOUBLE == primitiveTypeName) {
                    return g.getValueToString(index, 0);
                } else if (PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY == primitiveTypeName) {
                    return g.getValueToString(index, 0);
                } else if (PrimitiveType.PrimitiveTypeName.FLOAT == primitiveTypeName) {
                    return g.getValueToString(index, 0);
                } else if (PrimitiveType.PrimitiveTypeName.INT32 == primitiveTypeName) {
                    return g.getValueToString(index, 0);
                } else if (PrimitiveType.PrimitiveTypeName.INT64 == primitiveTypeName) {
                    return g.getValueToString(index, 0);
                } else if (PrimitiveType.PrimitiveTypeName.INT96 == primitiveTypeName) {
                    Binary dataInt96 = g.getInt96(index, 0);
                    if (null == dataInt96) {
                        return null;
                    } else {
                        ByteBuffer buf = dataInt96.toByteBuffer();
                        buf.order(ByteOrder.LITTLE_ENDIAN);
                        long timeOfDayNanos = buf.getLong();
                        int julianDay = buf.getInt();
                        if (isUtcTimestamp) {
                            // UTC
                            LocalDate localDate = LocalDate.ofEpochDay(julianDay - JULIAN_EPOCH_OFFSET_DAYS);
                            LocalTime localTime = LocalTime.ofNanoOfDay(timeOfDayNanos);
                            return Timestamp.valueOf(LocalDateTime.of(localDate, localTime));
                        } else {
                            // local time
                            long mills = julianDayToMillis(julianDay) + (timeOfDayNanos / NANOS_PER_MILLISECOND);
                            Timestamp timestamp = new Timestamp(mills);
                            timestamp.setNanos((int) (timeOfDayNanos % TimeUnit.SECONDS.toNanos(1)));
                            return timestamp;
                        }
                    }
                } else {
                    return g.getValueToString(index, 0);
                }
            } else {
                return g.getValueToString(index, 0);
            }
        }
    }

    private static org.apache.parquet.schema.OriginalType getOriginalType(org.apache.parquet.schema.Type type, Map<String, ParquetMeta> parquetMetaMap) {
        ParquetMeta meta = parquetMetaMap.get(type.getName());
        return meta.getOriginalType();
    }


    private static org.apache.parquet.schema.PrimitiveType asPrimitiveType(org.apache.parquet.schema.Type type, Map<String, ParquetMeta> parquetMetaMap) {
        ParquetMeta meta = parquetMetaMap.get(type.getName());
        return meta.getPrimitiveType();
    }
    private static long julianDayToMillis(int julianDay) {
        return (julianDay - JULIAN_EPOCH_OFFSET_DAYS) * MILLIS_IN_DAY;
    }
    private static final int JULIAN_EPOCH_OFFSET_DAYS = 2440588;
    private static final long MILLIS_IN_DAY = TimeUnit.DAYS.toMillis(1);
    private static final long NANOS_PER_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1);
}
