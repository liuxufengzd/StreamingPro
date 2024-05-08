package org.liu.app;

import org.apache.hadoop.hbase.client.Connection;
import org.apache.spark.api.java.function.MapPartitionsFunction;
import org.apache.spark.sql.*;
import org.liu.bean.TradeSkuOrderBean;
import org.liu.common.app.AppBase;
import org.liu.common.service.HBaseService;
import org.liu.common.util.HBaseConnectionUtil;
import org.liu.common.util.RedisUtil;
import org.liu.common.util.StreamUtil;
import redis.clients.jedis.Jedis;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.apache.spark.sql.functions.*;
import static org.liu.common.constant.Constant.*;

public class TradeSkuOrderApp extends AppBase {
    private Connection conn;

    public static void main(String[] args) {
        new TradeSkuOrderApp().start(args, 4);
    }

    @Override
    public void etl(SparkSession spark, String[] args) {
        Dataset<Row> source = deltaTableStream(spark, DWD_ORDER_DETAIL)
                .select("sku_id", "sku_name", "sku_num", "order_price", "create_time")
                .na().drop(new String[]{"sku_id", "create_time"});

        source = source.withWatermark("create_time", "1 seconds")
                .groupBy(col("sku_id"),
                        window(col("create_time"), "5 seconds"))
                .agg(sum("sku_num").as("total_sku_num"), sum("order_price").as("total_order_price"))
                .withColumn("startTime", col("window.start"))
                .withColumn("endTime", col("window.end"));

        /*
        Connect with dim_sku_info,dim_spu_info,dim_base_category3,dim_base_category2,dim_base_category1,dim_base_trademark
        They are stored in HBase for this testing
        Use Redis for in-memory database to decrease heavy connections with HBase and increase performance
        Here the connection with external systems is synchronous, Structured streaming cannot support async IO now
         */
        try {
            source.writeStream()
                    .option("checkpointLocation", StreamUtil.getTableCheckpointPath(DWS_LAYER, DWS_TRADE_SKU_ORDER))
                    .foreachBatch(this::process)
                    .start();
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    private void process(Dataset<Row> src, long id) {
        Dataset<TradeSkuOrderBean> data = src.mapPartitions((MapPartitionsFunction<Row, TradeSkuOrderBean>) rows -> {
            Jedis client = RedisUtil.getClient();
            ArrayList<TradeSkuOrderBean> res = new ArrayList<>();

            while (rows.hasNext()) {
                Row row = rows.next();
                String skuId = row.getAs("sku_id");
                TradeSkuOrderBean bean = new TradeSkuOrderBean();
                bean.setSkuId(skuId);
                bean.setStartTime(row.getAs("startTime"));
                bean.setEndTime(row.getAs("endTime"));
                bean.setDate(new SimpleDateFormat("yyyy-MM-dd").format(bean.getEndTime()));
                bean.setTotalSkuNum(row.getAs("total_sku_num"));
                bean.setTotalOrderPrice(row.getAs("total_order_price"));

                Map<String, String> info = getInfo(DIM_SKU_INFO, skuId, new String[]{"category3_id", "tm_id", "spu_id", "sku_name"}, client);
                bean.setSkuName(info.get("sku_name"));
                bean.setSpuId(info.get("spu_id"));
                bean.setTrademarkId(info.get("tm_id"));
                bean.setCategory3Id(info.get("category3_id"));

                info = getInfo(DIM_SPU_INFO, bean.getSpuId(), new String[]{"spu_name"}, client);
                bean.setSpuName(info.get("spu_name"));

                info = getInfo(DIM_BASE_CATEGORY3, bean.getCategory3Id(), new String[]{"name", "category2_id"}, client);
                bean.setCategory3Name(info.get("name"));
                bean.setCategory2Id(info.get("category2_id"));

                info = getInfo(DIM_BASE_CATEGORY2, bean.getCategory2Id(), new String[]{"name", "category1_id"}, client);
                bean.setCategory2Name(info.get("name"));
                bean.setCategory1Id(info.get("category1_id"));

                info = getInfo(DIM_BASE_CATEGORY1, bean.getCategory1Id(), new String[]{"name"}, client);
                bean.setCategory1Name(info.get("name"));

                info = getInfo(DIM_BASE_TRADEMARK, bean.getTrademarkId(), new String[]{"tm_name"}, client);
                bean.setTrademarkName(info.get("tm_name"));

                res.add(bean);
            }

            HBaseConnectionUtil.closeConnection(conn);
            RedisUtil.closeClient(client);

            return res.iterator();
        }, Encoders.bean(TradeSkuOrderBean.class));

        if (!data.isEmpty()) {
            data.write().format("delta")
                    .mode(SaveMode.Append)
                    .partitionBy("date")
                    .option("path", StreamUtil.getTablePath(DWS_LAYER, DWS_TRADE_SKU_ORDER))
                    .saveAsTable(DELTA_DB + "." + DWS_TRADE_SKU_ORDER);
        }
    }

    private Map<String, String> getInfo(String table, String id, String[] columns, Jedis client) {
        Map<String, String> info;
        String key = RedisUtil.getKey(table, id);
        // Read HBase if redis not cached the row
        if (!client.exists(key)) {
            if (conn == null) conn = HBaseConnectionUtil.newConnection();
            HBaseService service = new HBaseService(conn);
            info = service.getColumns(DATABASE, table, id, "info", columns);
            info.forEach((k, v) -> client.hset(key, k, v));
            client.expire(key, 24 * 60 * 60);
        } else {
            // Read redis cache to avoid to create heavy HBase connection and expedite row fetching
            // But if some columns are not cached, have to read from HBase and cache them
            info = client.hgetAll(key);
            for (String column : columns) {
                if (!info.containsKey(column)) {
                    if (conn == null) conn = HBaseConnectionUtil.newConnection();
                    HBaseService service = new HBaseService(conn);
                    String value = service.getColumn(DATABASE, table, id, "info", column);
                    info.put(column, value);
                    client.hset(key, column, value);
                }
            }
        }
        return info;
    }
}
