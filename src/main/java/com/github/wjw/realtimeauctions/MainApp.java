package com.github.wjw.realtimeauctions;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Properties;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.spi.cluster.zookeeper.ZookeeperClusterManager;

public class MainApp {
  public static void main(String[] args) {
    {//@wjw_note: 设置环境为开发环境,关闭文件缓存和模板缓存!
     //1. During development you might want to disable template caching so that the template gets reevaluated on each request. 
     //In order to do this you need to set the system property: vertxweb.environment or environment variable VERTXWEB_ENVIRONMENT to dev or development. 
     //By default caching is always enabled.

      //2. these system properties are evaluated once when the io.vertx.core.file.FileSystemOptions class is loaded, 
      //so these properties should be set before loading this class or as a JVM system property when launching it.

      System.setProperty("vertxweb.environment", "dev");
      //@wjw_note: 调试发现,当有众多的小js,css文件时,,Vertx总是用原始源刷新缓存中存储的版本,严重影响性能      
      System.setProperty("vertx.disableFileCaching", "true");
    }

    //防止调试的时候出现`BlockedThreadChecker`日志信息
    VertxOptions options                    = new VertxOptions();
    long         blockedThreadCheckInterval = 60 * 60 * 1000L;
    if (System.getProperties().getProperty("vertx.options.blockedThreadCheckInterval") != null) {
      blockedThreadCheckInterval = Long.valueOf(System.getProperties().getProperty("vertx.options.blockedThreadCheckInterval"));
    }
    options.setBlockedThreadCheckInterval(blockedThreadCheckInterval);

    // 校验是否指定了`profile`参数,和相应的配置文件是否存在!
    String profile;
    Properties sysProperties = System.getProperties();
    profile = sysProperties.getProperty("profile");
    if (profile == null) {
      System.out.println("Please set 'profile'");
      System.exit(-1);
      return;
    }

    // 部署 Verticle
    if (args.length > 0 && args[0].equalsIgnoreCase("-cluster")) {
      //@wjw_note: 加载zookeeper配置文件!
      System.getProperties().setProperty("vertx.zookeeper.config", "config/zookeeper-" + profile + ".json");
      ClusterManager mgr = new ZookeeperClusterManager();
      options.setClusterManager(mgr);

      Vertx.clusteredVertx(options).onSuccess(it -> {
        deployVerticle(it);
      }).onFailure(throwable -> {
        throwable.printStackTrace();
        System.exit(-1);
      });
    } else {
      Vertx vertx = Vertx.vertx(options);
      deployVerticle(vertx);
    }
  }

  private static void deployVerticle(Vertx vertx) {
    Future<String> deployFuture = vertx.deployVerticle(new AuctionServiceVerticle());
    deployFuture.onComplete(ar -> {
      if (System.getProperties().getProperty("profile").equalsIgnoreCase("dev")) { //说明在IDE里运行的,这时候等待命令行输入quit来优雅的退出!
        System.out.println("You're using Eclipse; click in this console and " + "input quit/exit to call System.exit() and run the shutdown routine.");

        startIdeConsole(vertx);
      }
    });
  }

  private static void startIdeConsole(Vertx vertx) {
    Thread inputThread = new Thread(() -> {
      boolean loopz = true;
      try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
        while (loopz) {
          String userInput = br.readLine();
          System.out.println("input => " + userInput);
          if (userInput.equalsIgnoreCase("quit") || userInput.equalsIgnoreCase("exit")) {
            vertx.close().onComplete(it -> {
              System.out.println("Shutdown hook run.");
              System.exit(0);
            });
          }
        }
      } catch (Exception er) {
        er.printStackTrace();
        loopz = false;
      }
    });
    inputThread.setDaemon(true);
    inputThread.start();
  }
}
