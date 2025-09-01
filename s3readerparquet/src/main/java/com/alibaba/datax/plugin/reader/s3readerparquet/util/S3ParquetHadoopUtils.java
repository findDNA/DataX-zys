package com.alibaba.datax.plugin.reader.s3readerparquet.util;

import com.alibaba.datax.common.element.*;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordSender;
import com.alibaba.datax.common.plugin.TaskPluginCollector;
import com.alibaba.datax.plugin.reader.s3readerparquet.HdfsReaderErrorCode;
import com.alibaba.datax.plugin.reader.s3readerparquet.ParquetMessageHelper;
import com.alibaba.datax.plugin.reader.s3readerparquet.ParquetMeta;
import com.alibaba.datax.plugin.reader.s3readerparquet.S3Key;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.ql.exec.vector.*;
import org.apache.orc.OrcFile;
import org.apache.orc.TypeDescription;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.apache.parquet.schema.PrimitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class S3ParquetHadoopUtils {
    private static final Logger LOG = LoggerFactory.getLogger(S3ParquetHadoopUtils.class);
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
        List<org.apache.parquet.schema.Type> parquetTypes = null;
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
            int row=0;
            while ((record = reader.read()) != null) {
                List<Object> formattedRecord = new ArrayList<Object>(fieldCount);
                for (int j = 0; j < fieldCount; j++) {
                    Object data=null;
                    try {
                        data = readFields(record, parquetTypes.get(j), j, parquetMetaMap, false);
                    }catch (RuntimeException e){
                        System.out.println("error:"+e.getMessage());
                    }
                    formattedRecord.add(data);
                }
                System.out.println("record: "+formattedRecord);
                row++;
            }
            System.out.println("total row:"+row);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private enum Type {
        STRING, LONG, BOOLEAN, DOUBLE, DATE,
    }
    // 保留秒
   private static final DateTimeFormatter f1 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static Record transportOneRecord( List<Object> recordFields
            , RecordSender recordSender, TaskPluginCollector taskPluginCollector, boolean isReadAllColumns) {
        Record record = recordSender.createRecord();
        Column columnGenerated;
        try {
            if (isReadAllColumns) {
                // 读取所有列，创建都为String类型的column
                for (Object recordField : recordFields) {
                    String columnValue = null;
                    if (recordField != null) {
                        columnValue = recordField.toString();
                    }
                    columnGenerated = new StringColumn(columnValue);
                    record.addColumn(columnGenerated);
                }
            }
            recordSender.sendToWriter(record);
        } catch (IllegalArgumentException iae) {
            taskPluginCollector
                    .collectDirtyRecord(record, iae.getMessage());
        } catch (IndexOutOfBoundsException ioe) {
            taskPluginCollector
                    .collectDirtyRecord(record, ioe.getMessage());
        } catch (Exception e) {
            if (e instanceof DataXException) {
                throw (DataXException) e;
            }
            // 每一种转换失败都是脏数据处理,包括数字格式 & 日期格式
            taskPluginCollector.collectDirtyRecord(record, e.getMessage());
        }

        return record;
    }

    public static void readFromStream(String object, com.alibaba.datax.common.util.Configuration readerSliceConfig, RecordSender recordSende, TaskPluginCollector taskPluginCollector){
        String bucket = readerSliceConfig.getString(S3Key.BUCKET);
        String accessId = readerSliceConfig.getString(S3Key.ACCESS_ID);
        String accessKey = readerSliceConfig.getString(S3Key.ACCESS_KEY);
        String endpoint = readerSliceConfig.getString(S3Key.ENDPOINT);
        String fileformat = readerSliceConfig.getString(S3Key.FILE_FORMAT);
        Configuration conf = new Configuration();
        conf.set("fs.s3a.access.key",  accessId);
        conf.set("fs.s3a.secret.key",  accessKey);
        // 如果使用 MinIO、Ceph 等 S3 兼容服务，则加 endpoint
        conf.set("fs.s3a.endpoint", endpoint);
        conf.set("fs.s3a.experimental.fadvise", "sequential");
        conf.set("fs.s3a.readahead.range", "16M");
        Path path = new Path("s3a://"+bucket+"/"+object);
        String schemaString = null;
        MessageType parquetSchema = null;
        List<org.apache.parquet.schema.Type> parquetTypes = null;
        Map<String, ParquetMeta> parquetMetaMap = null;
        ParquetFileReader filereader =null;
        int fieldCount = 0;
        if(fileformat.equalsIgnoreCase("parquet")){
            try {
                //schemaString=getParquetSchema(path.toString(), conf);
                LOG.info("getParquetSchema {} from object {}",schemaString,object);
                filereader = ParquetFileReader.open(HadoopInputFile.fromPath(path, conf));
                parquetSchema = filereader.getFileMetaData().getSchema();
                fieldCount = parquetSchema.getFieldCount();
                parquetTypes = parquetSchema.getFields();
                parquetMetaMap = ParquetMessageHelper.parseParquetTypes(parquetTypes);
            } catch (Exception e) {
                String message = String.format("Error parsing to MessageType via Schema string [%s]", schemaString);
                LOG.error(message);
                throw DataXException.asDataXException(HdfsReaderErrorCode.PARSE_MESSAGE_TYPE_FROM_SCHEMA_ERROR, e);
            }
            try {
                PageReadStore pages;
                int row=0;
                while ((pages = filereader.readNextRowGroup()) != null) {
                    long rowCount = pages.getRowCount();
                    MessageColumnIO columnIO = new ColumnIOFactory().getColumnIO(parquetSchema);
                    RecordReader<Group> recordReader = columnIO.getRecordReader(pages, new GroupRecordConverter(parquetSchema));

                    for (int i = 0; i < rowCount; i++) {
                        Group record = recordReader.read();
                        // 处理 group
                        List<Object> formattedRecord = new ArrayList<Object>(fieldCount);
                        for (int j = 0; j < fieldCount; j++) {
                            Object data=null;
                            try {
                                data = readFields(record, parquetTypes.get(j), j, parquetMetaMap, false);
                            }catch (RuntimeException e){
                                LOG.debug("error: {}",e.getMessage());
                            }
                            formattedRecord.add(data);
                        }
                        LOG.debug("read row record: {}",formattedRecord);
                        transportOneRecord(formattedRecord, recordSende, taskPluginCollector, true);
                        row++;
                    }
                    LOG.debug("read row count: {}",row);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }else if (fileformat.equalsIgnoreCase("orc")){
// 2. 打开 ORC 文件
            OrcFile.ReaderOptions opts = OrcFile.readerOptions(conf);
            try (org.apache.orc.Reader reader = OrcFile.createReader(path, opts)) {
                // 只读需要的列（列裁剪）
                TypeDescription schema = reader.getSchema();
                // 3. 顺序读取 stripe
                org.apache.orc.RecordReader rows = reader.rows(reader.options()
                        .range(0, Long.MAX_VALUE));   // 顺序范围
                // 2. 根据实际列数创建 batch
                VectorizedRowBatch batch = reader.getSchema().createRowBatch(1024);
                fieldCount = reader.getSchema().getMaximumId();
                while (rows.nextBatch(batch)) {
                    for (int r = 0; r < batch.size; r++) {
                        List<Object> record = new ArrayList<>(fieldCount);
//                        for (int c = 0; c < fieldCount; c++) {
//                            ColumnVector cv = batch.cols[c];
//                            if (cv.isNull[r]) {
//                                record.add(null);
//                                continue;
//                            }
//                            // 根据列类型安全取值
//                            switch (cv.type) {
//                                case LONG:
//                                    record.add(((LongColumnVector) cv).vector[r]);
//                                    break;
//                                case DOUBLE:
//                                    record.add(((DoubleColumnVector) cv).vector[r]);
//                                    break;
//                                case BYTES:
//                                    BytesColumnVector bcv = (BytesColumnVector) cv;
//                                    record.add(new String(bcv.vector[r], bcv.start[r], bcv.length[r], StandardCharsets.UTF_8));
//                                    break;
//                                case DECIMAL:
//                                    // ORC 里 DECIMAL 存储为 HiveDecimalWritable
//                                    record.add(((DecimalColumnVector) cv).vector[r].getHiveDecimal().bigDecimalValue().toString());
//                                    break;
//                                case TIMESTAMP:
//                                    record.add(((TimestampColumnVector) cv).time[r]);
//                                    break;
//                                default:
//                                    record.add(null); // 兜底
//                            }
//                        }
                        for (int c = 0; c < schema.getChildren().size(); c++) {
                            Object o = convertCell(schema.getChildren().get(c), batch.cols[c], r);
                            record.add(o);
                        }
                        transportOneRecord(record, recordSende, taskPluginCollector,true);
                    }
                }
                rows.close();
            }catch (Exception e){
                throw new RuntimeException(e);
            }

        }

    }

    /** 把 ORC 单元格转成 Java 对象（核心函数） */
    private static Object convertCell(TypeDescription type, ColumnVector cv, int row) {
        if (cv.isNull[row]) {               // NULL 值
            return null;
        }
        switch (type.getCategory()) {
            case BOOLEAN:
                return ((LongColumnVector) cv).vector[row] != 0;
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
                return ((LongColumnVector) cv).vector[row];
            case FLOAT:
            case DOUBLE:
                return ((DoubleColumnVector) cv).vector[row];
            case DECIMAL:
                return ((DecimalColumnVector) cv).vector[row].getHiveDecimal().bigDecimalValue().toString();   // BigDecimal
            case STRING:
            case CHAR:
            case VARCHAR:
                return ((BytesColumnVector) cv).toString(row);                     // String
            case DATE:
                int epochDay = (int) ((LongColumnVector) cv).vector[row];
                return LocalDate.ofEpochDay(epochDay);                             // LocalDate
            case TIMESTAMP:
                TimestampColumnVector tsv = (TimestampColumnVector) cv;
                long millis = tsv.time[row];
                int  nanos  = tsv.nanos[row];
                LocalDateTime localDateTime = LocalDateTime.ofEpochSecond(
                        millis / 1000, nanos, ZoneOffset.ofHours(+8));
               return localDateTime.format(f1);  // LocalDateTime
            case BINARY:
                BytesColumnVector bcv = (BytesColumnVector) cv;
                return new String(bcv.vector[row], bcv.start[row], bcv.length[row], StandardCharsets.UTF_8);// byte[]
            case STRUCT:
            case LIST:
            case MAP:
            case UNION:
            default:
                throw new UnsupportedOperationException("Unsupported type: " + type);
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
