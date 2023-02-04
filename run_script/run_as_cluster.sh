#!/bin/sh

basedir=`dirname $0`
echo "BASE DIR:$basedir"
cd $basedir

# 指定一个外部文件为自定义配置文件
java -Dvertx.zookeeper.config=./config/zookeeper.json -jar simple-realtime-auctions-vertx4-example-1.0-fat.jar -cluster

# 从 classpath 中加载一个文件为自定义配置文件
#java -Dvertx.zookeeper.config=classpath:config/zookeeper.json -jar simple-realtime-auctions-vertx4-example-1.0-fat.jar -cluster
