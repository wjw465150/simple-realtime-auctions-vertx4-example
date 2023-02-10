/*
 * author: @wjw
 * date:   2023年2月2日 下午6:12:45
 * note: 
 */
package com.github.wjw.realtimeauctions;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.ErrorHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.ext.web.handler.ResponseTimeHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.SockJSBridgeOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;

public class AuctionServiceVerticle extends AbstractVerticle {
  public final static Integer PORT = 9090;

  private Logger logger;

  private Map<String, MessageConsumer<JsonObject>> wsUsers = new HashMap<>();

  public AuctionServiceVerticle() {
    this.logger = LoggerFactory.getLogger(this.getClass());
  }

  @Override
  public void start(Promise<Void> startPromise) throws Exception {
    String vertx_config_path;
    String profile;
    { //->校验是否指定了`profile`参数,和相应的配置文件是否存在!
      Properties sysProperties = System.getProperties();
      profile = sysProperties.getProperty("profile");
      if (profile == null) {
        System.out.println("Please set 'profile'");
        this.vertx.close();
        return;
      }

      //@wjw_note: 为了从classpath里加载配置文件!
      //也可以通过系统属性`vertx-config-path`来覆盖: java -jar my-vertx-first-app-1.0-SNAPSHOT--prod-fat.jar -Dvertx-config-path=config/conf-prod.json
      vertx_config_path = sysProperties.getProperty("vertx-config-path");
      if (vertx_config_path == null) { //如果系统属性`vertx-config-path`没有被设置
        vertx_config_path = "config/conf-" + profile + ".json";
      }

    } //<-校验是否指定了`profile`参数,和相应的配置文件是否存在!

    //加载配置文件
    ConfigStoreOptions     classpathStore = new ConfigStoreOptions()
        .setType("file")
        .setConfig(new JsonObject().put("path", vertx_config_path));
    ConfigRetrieverOptions configOptions  = new ConfigRetrieverOptions().addStore(classpathStore);
    ConfigRetriever        retriever      = ConfigRetriever.create(vertx, configOptions);

    retriever.getConfig().onSuccess(json -> {
      {//@wjw_note: 加载log的配置文件!
        try {
          String log_config_path = json.getString("logging");
          LogBackConfigLoader.load(log_config_path);
          logger.info("Logback configure file: " + log_config_path);
        } catch (Exception e) {
          e.printStackTrace();
          startPromise.fail(e);
        }
      }
      logger.info("!!!!!!=================Vertx App profile:" + profile + "=================!!!!!!");

      Router router = Router.router(vertx);

      { //先route基础handler
        router.route().failureHandler(errorHandler()); //将故障处理程序附加到路由故障处理程序列表。
        router.route().handler(staticHandler()); //Vert.x-Web 带有一个开箱即用的处理程序，用于提供静态 Web 资源
        router.route().handler(ResponseTimeHandler.create()); //此处理程序设置标头`x-response-time`响应标头，其中包含从收到请求到写入响应标头的时间，以毫秒为单位
        if (profile.equalsIgnoreCase("prod") == false) { //生产状态不记录web日志
          //router.route().handler(LoggerHandler.create()); //Vert.x-Web 包含一个处理程序`LoggerHandler`，您可以使用它来记录 HTTP 请求。 您应该在任何可能使 `RoutingContext` 失败的处理程序之前安装此处理程序
        }
      }

      router.route("/eventbus/*").subRouter(eventBusHandler()); //安装处理event-bus的子路由
      router.route("/api/*").subRouter(auctionApiRouter()); //安装处理竞价的子路由

      vertx.createHttpServer()
          .requestHandler(router)
          .listen(json.getInteger("http.port", PORT))
          .onSuccess(server -> {
            logger.info("Realtime Auctions Server start OK! listen port: " + server.actualPort());
            startPromise.complete();
          })
          .onFailure(throwable -> startPromise.fail(throwable));
    });
  }

  /**
   * Event bus handler.
   * <p>
   * 将 SockJS 处理程序桥接到 Vert.x 事件总线。 这基本上安装了一个内置的 SockJS 套接字处理程序， 它接收 SockJS
   * 流量并将其桥接到事件总线， 从而允许您将服务器端 Vert.x 事件总线扩展到浏览器
   * 
   * @return the router
   */
  private Router eventBusHandler() {
    SockJSBridgeOptions bridgeOptions = new SockJSBridgeOptions();
    bridgeOptions.addOutboundPermitted(new PermittedOptions().setAddressRegex("auction\\.[0-9]+"));
    bridgeOptions.addInboundPermitted(new PermittedOptions().setAddressRegex("auction\\.[0-9]+"));

    bridgeOptions.addOutboundPermitted(new PermittedOptions().setAddressRegex("user\\..+"));
    bridgeOptions.addInboundPermitted(new PermittedOptions().setAddressRegex("user\\..+"));

    SockJSHandler sockJSHandler = SockJSHandler.create(vertx);

    return sockJSHandler.bridge(bridgeOptions, event -> {
      if (event.type() == BridgeEventType.SOCKET_CREATED) {
        logger.info("A WebSocket was created,uri: '" + event.socket().uri() + "'");
      } else if (event.type() == BridgeEventType.SOCKET_CLOSED) {
        logger.info("A WebSocket was closed,uri: '" + event.socket().uri() + "'");
        
        //移除event.socket().uri()的consumer
        String webSocketKey = event.socket().uri().replaceAll("/", ".");

        MessageConsumer<JsonObject> wsUserIdconsumer = wsUsers.remove(webSocketKey);
        if (wsUserIdconsumer != null) {
          wsUserIdconsumer.unregister().onComplete(it -> {
            logger.info("The consumer '" + wsUserIdconsumer.address() + "' unregister has reached all nodes:");
          });
        }
        
      } else if (event.type() == BridgeEventType.REGISTERED) {
        logger.info("A WebSocket was registered,uri: '" + event.socket().uri() + "', rawMessage: " + event.getRawMessage().encode());

        String myid = event.getRawMessage().getJsonObject("headers").getString("myid");  // 从headers获取到myid
        if (myid != null) {
          String webSocketKey = event.socket().uri().replaceAll("/", ".");  // TODO: 这里如果把传来的myid作为key,当一个用户同时打开多个TAB页时会覆盖前面打开的!

          MessageConsumer<JsonObject> wsUserIdconsumer = vertx.eventBus().<JsonObject> consumer("user." + myid, message -> {
            String rMsg = "收到了: address:" + message.address() + " body: " + message.body();
            System.out.println(rMsg);
            message.reply(rMsg);
          });
          wsUserIdconsumer.completionHandler(res -> {  // 当consumer()调用成功后,把MessageConsumer放到wsUsers这个Map里
            if (res.succeeded()) {
              wsUsers.put(webSocketKey, wsUserIdconsumer);
            } else {
              logger.error("The wsUserIdconsumer '" + webSocketKey + "' Registration failed! cause: " + res.cause());
            }
          });

        }
      }

      event.complete(true); //使用“true”完成`Promise`以启用进一步处理
    });
  }

  /**
   * Auction api router.
   * <p>
   * 处理客户端发来的HTTP竞拍信息
   * 
   * @return the router
   */
  private Router auctionApiRouter() {
    AuctionRepository repository = new AuctionRepository(vertx.sharedData());
    AuctionValidator  validator  = new AuctionValidator(repository);
    AuctionHandler    handler    = new AuctionHandler(repository, validator);

    Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create()); //`BodyHandler` 允许您检索请求正文、限制正文大小和处理文件上传。

    //只接收和返回json格式数据
    router.route().consumes("application/json");
    router.route().produces("application/json");

    router.route("/auctions/:id").handler(handler::initAuctionInSharedData);
    router.get("/auctions/:id").handler(handler::handleGetAuction);
    router.patch("/auctions/:id").handler(handler::handleChangeAuctionPrice);

    return router;
  }

  /**
   * Error handler.
   *
   * @return the error handler
   */
  private ErrorHandler errorHandler() {
    return ErrorHandler.create(vertx, true);
  }

  /**
   * Static handler.
   *
   * @return the static handler
   */
  private StaticHandler staticHandler() {
    return StaticHandler.create()
        .setCachingEnabled(false);
  }
}
