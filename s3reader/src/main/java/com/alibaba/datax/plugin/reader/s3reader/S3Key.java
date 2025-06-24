package com.alibaba.datax.plugin.reader.s3reader;


import com.alibaba.datax.plugin.unstructuredstorage.reader.Key;

public class S3Key extends Key
{
    public static final String REGION = "region";

    public static final String ENDPOINT = "endpoint";

    public static final String ACCESS_ID = "accessId";

    public static final String ACCESS_KEY = "accessKey";

    public static final String BUCKET = "bucket";

    public static final String OBJECT = "object";

    public static final String PATH_STYLE_ACCESS_ENABLED = "pathStyleAccessEnabled";
    public static final String ENCODING = "encoding";
    public static final String COLUMN_LIST = "columnList";
    public static final String COLUMN = "column";
    // The field's position. string/numeric type
    public static final String INDEX = "index";
    // The field's data type. string type
    public static final String TYPE = "type";
    // The field's value. string/numeric type
    public static final String VALUE = "value";
}
