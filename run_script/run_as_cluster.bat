REM 进入当前批处理文件所在的目录
cd /d %~dp0

REM 指定一个外部文件为自定义配置文件
java -Dprofile=test -Dvertx.zookeeper.config=./config/zookeeper-test.json -jar simple-realtime-auctions-vertx4-example-1.0-test-fat.jar -cluster

REM 从 classpath 中加载一个文件为自定义配置文件
REM java -Dprofile=test -Dvertx.zookeeper.config=classpath:config/zookeeper-test.json -jar simple-realtime-auctions-vertx4-example-1.0-test-fat.jar -cluster
