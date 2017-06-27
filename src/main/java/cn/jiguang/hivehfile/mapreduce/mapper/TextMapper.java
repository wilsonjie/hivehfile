package cn.jiguang.hivehfile.mapreduce.mapper;

import cn.jiguang.hivehfile.model.MappingInfo;
import cn.jiguang.hivehfile.util.DateUtil;
import cn.jiguang.hivehfile.util.PrintUtil;
import cn.jiguang.hivehfile.util.XmlUtil;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.text.ParseException;

/**
 * Created by: fitz
 * <p>
 * Date: 2017/6/24
 * <p>
 * Description:
 */
public class TextMapper extends Mapper<LongWritable, Text, ImmutableBytesWritable, KeyValue> {
    private Logger logger = LogManager.getLogger(TextMapper.class);
    private cn.jiguang.hivehfile.Configuration selfDefinedConfig = null;

    @Override
    public void setup(Context context) throws IOException {
        // 读取HDFS配置文件，并将其封装成对象
        selfDefinedConfig = XmlUtil.generateConfigurationFromXml(context.getConfiguration(), context.getConfiguration().get("config.file.path"));
    }

    @Override
    public void map(LongWritable key, Text value, Mapper.Context context) throws IOException, InterruptedException {
        String inputString = value.toString();
        // 获取数据文件的路径
        String dataFilePath = ((FileSplit) context.getInputSplit()).getPath().toString();
        String[] values = inputString.split(selfDefinedConfig.getDelimiterCollection().get("field-delimiter"));
        // 获取当前 MappingInfo
        MappingInfo currentMappingInfo = XmlUtil.extractCurrentMappingInfo(dataFilePath, selfDefinedConfig.getMappingInfoList());
        // 检验 MappingInfo 中，ColumnMapping 数目是否与数据文件字段数匹配
        if(!currentMappingInfo.isColumnMatch(values.length)){
            throw new InterruptedException("配置文件校验失败，配置文件的column-mapping数目与数据文件不匹配！");
        }
        // 在每一行数据中，rowkey 和 timestamp 都固定不变
        ImmutableBytesWritable rowkey = new ImmutableBytesWritable(Bytes.toBytes(values[XmlUtil.extractRowkeyIndex(currentMappingInfo)]));
        Long ts = 0L;
            /*
             * 解析数据文件路径，获取数据日期 data_date
             * 当数据文件路径中不含有 data_date 时，默认使用当前时间
             */
        try {
            ts = DateUtil.convertStringToUnixTime(dataFilePath, "yyyyMMdd", "data_date=(\\d{8})");
        } catch (ParseException e) {
            logger.fatal("无法解析数据日期，请检查InputPath和Partition的填写！");
            System.exit(-1);    // 异常直接退出
        }
            /* 开始装配HFile
             * 所需参数：
             * RowKey
             * ColumnFamily
             * ColumnQualifier
             * TimeStamp
             * Value
             */
        for (int i = 0; i < values.length; i++) {
            KeyValue kv = null;
            if (i != XmlUtil.extractRowkeyIndex(currentMappingInfo)
                    && currentMappingInfo.getColumnMappingList().get(i).get("hbase-column-family") != null
                    && currentMappingInfo.getColumnMappingList().get(i).get("hbase-column-qualifier") != null
                    ) {  // 只遍历非 Rowkey 且 需要写入 HBase 的字段
                try {
                    String transformedValue = PrintUtil.escapeConnotation(values[i]);
                    // 字段取值可能为空，将所有空值 \\N 转换为空串
                    if ("\\N".equals(transformedValue)) {
                        transformedValue = "";
                    }
                    kv = new KeyValue(Bytes.toBytes(values[XmlUtil.extractRowkeyIndex(currentMappingInfo)]),
                            Bytes.toBytes(currentMappingInfo.getColumnMappingList().get(i).get("hbase-column-family")),
                            Bytes.toBytes(currentMappingInfo.getColumnMappingList().get(i).get("hbase-column-qualifier")),
                            ts,
                            Bytes.toBytes(transformedValue)
                    );
                } catch (Exception e) {
                    logger.error("异常数据：" + values[XmlUtil.extractRowkeyIndex(currentMappingInfo)] + ":" +
                            PrintUtil.escapeConnotation(values[i]));
                    logger.error(e.getMessage());
                }
            }
            if (kv != null) context.write(rowkey, kv);
        }
    }
}