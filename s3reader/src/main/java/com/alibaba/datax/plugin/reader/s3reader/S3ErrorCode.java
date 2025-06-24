package com.alibaba.datax.plugin.reader.s3reader;

import com.alibaba.datax.common.spi.ErrorCode;

/**
 *
 */
public enum S3ErrorCode implements ErrorCode {

    SUCCESS_FILE_ERROR("S3-00", "未检测到SUCCESS文件错误！");

    private final String code;

    private final String describe;

    private S3ErrorCode(String code, String describe) {
        this.code = code;
        this.describe = describe;
    }

    @Override
    public String getCode() {
        return this.code;
    }

    @Override
    public String getDescription() {
        return this.describe;
    }

    @Override
    public String toString() {
        return String.format("Code:[%s], Describe:[%s]", this.code,
                this.describe);
    }

}
