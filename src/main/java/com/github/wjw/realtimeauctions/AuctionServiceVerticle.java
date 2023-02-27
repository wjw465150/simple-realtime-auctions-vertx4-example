/*
 * author: @wjw
 * date:   2023年2月2日 下午6:12:45
 * note: 
 */
package com.github.wjw.realtimeauctions;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.ErrorHandler;
import io.vertx.ext.web.handler.ResponseTimeHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeEvent;
import io.vertx.ext.web.handler.sockjs.SockJSBridgeOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;

public class AuctionServiceVerticle extends AbstractVerticle {
  public final static Integer PORT              = 9090;
  private static final String SEC_WEBSOCKET_KEY = "sec-websocket-key";

  private Logger logger;
  private String profile;

  //redis客户端
  RedissonClient redisson;

  private Map<String, String> socketUriAndPageIdMap = new HashMap<>();
  private Map<String, String> socketUriAndUserIdMap = new HashMap<>();

  private Map<String, MessageConsumer<JsonObject>> pageIdAndConsumerUserMsgMap = new HashMap<>();

  private static final String USER_AND_PAGE_MAP_NAME = "UserAndPage";
  //key是userId, value是一个JSON对象(里面key是socketUri,value是pageId)
  //服务器与客户端的User之间的point-to_point通信,有可能用户打开多个page,通信落在不同的服务器上,所有不能是local的
  private RMap<String, JsonObject> userIdAndSocketUri_PageRMap;

  public AuctionServiceVerticle() {
    this.logger = LoggerFactory.getLogger(this.getClass());
  }

  @Override
  public void start(Promise<Void> startPromise) throws Exception {
    String vertx_config_path;
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
      vertx.getOrCreateContext().config().mergeIn(json, true);
      vertx.getOrCreateContext().config().put("profile", profile);

      //初始化redisson
      String jsonRedissonConfig = json.getJsonObject("redis").encode();
      try {
        Config config = Config.fromJSON(jsonRedissonConfig);
        this.redisson = Redisson.create(config);
        vertx.getOrCreateContext().put("redis", redisson);
      } catch (IOException e) {
        logger.error(e.getMessage(), e);
        startPromise.fail(e);
        return;
      }
      userIdAndSocketUri_PageRMap = redisson.getMap(profile + "_" + USER_AND_PAGE_MAP_NAME);

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

  @Override
  public void stop(Promise<Void> stopPromise) throws Exception {
    Redisson tmpRedisson = vertx.getOrCreateContext().<Redisson> get("redis");
    tmpRedisson.shutdown();

    stopPromise.complete();
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
    /*
     * 对 服务端&客户端 保持连接的的注解 服务端: bridgeOptions.setPingTimeout(33L * 1000);
     * 是服务端等待客户端发送ping消息的超时时间,如果超过这个时间服务端就会主动关闭websocket连接(默认是10秒)
     * sockJSHandlerOptions.setHeartbeatInterval(30L * 1000);
     * 是服务端向客户端下发心跳消息(一个字符h)的间隔时间 客户端: eventBus = new EventBus('/eventbus', {
     * server: 'ProcessOn', sessionId: 10, timeout:
     * 60000,vertxbus_ping_interval: 30000 });
     * `vertxbus_ping_interval`是客户端向服务端发送ping消息的间隔时间 文本方式:
     * ["{\"type\":\"ping\"}"] 二进制方式: {"type":"ping"}
     */
    SockJSBridgeOptions bridgeOptions = new SockJSBridgeOptions();
    bridgeOptions.setPingTimeout(33L * 1000); //是服务端等待客户端发送ping消息的超时时间,如果超过这个时间服务端就会主动关闭websocket连接(默认是10秒)

    bridgeOptions.addOutboundPermitted(new PermittedOptions().setAddressRegex("auction\\..+")); //"auction\\.[0-9]+"
    bridgeOptions.addInboundPermitted(new PermittedOptions().setAddressRegex("auction\\..+"));

    bridgeOptions.addOutboundPermitted(new PermittedOptions().setAddressRegex("user\\..+"));
    bridgeOptions.addInboundPermitted(new PermittedOptions().setAddressRegex("user\\..+"));

    bridgeOptions.addOutboundPermitted(new PermittedOptions().setAddressRegex("page\\..+"));
    bridgeOptions.addInboundPermitted(new PermittedOptions().setAddressRegex("page\\..+"));

    SockJSHandlerOptions sockJSHandlerOptions = new SockJSHandlerOptions();
    if (profile.equalsIgnoreCase("dev")) { //如果是开发环境把session超时设置长一些(默认是5秒)
      sockJSHandlerOptions.setSessionTimeout(10L * 1000); //是客户端建立socket连接后,第一次发送数据包的超时时间(默认是5秒)
    }
    sockJSHandlerOptions.setHeartbeatInterval(30L * 1000); //是服务端向客户端下发心跳消息(一个字符h)的间隔时间
    sockJSHandlerOptions.setRegisterWriteHandler(false); //@wjw_note: 用了`eventbus bridge`方式就不能再使用`writeHandler`
    SockJSHandler sockJSHandler = SockJSHandler.create(vertx, sockJSHandlerOptions);

    return sockJSHandler.bridge(bridgeOptions, event -> {
      if (event.type() == BridgeEventType.SOCKET_CREATED) {
        //TODO: 对`event.socket().headers().get()`获取,键是不区分大小写的!
        String socketUri = (event.socket().uri() + "-" + event.socket().headers().get(SEC_WEBSOCKET_KEY))
            .replaceAll("/", ".");

        logger.info(MessageFormat.format("A WebSocket was created,socketId: `{0}`", socketUri));
      } else if (event.type() == BridgeEventType.SOCKET_CLOSED) {
        onSocketClosed(event);
      } else if (event.type() == BridgeEventType.REGISTERED) {
        onSocketRegistered(event);
      } else if (event.type() == BridgeEventType.UNREGISTER) { //当直接关闭页面时会先触发UNREGISTER事件,再触发SOCKET_CLOSED事件
        String socketUri = (event.socket().uri() + "-" + event.socket().headers().get(SEC_WEBSOCKET_KEY))
            .replaceAll("/", ".");

        logger.info(MessageFormat.format("A WebSocket was unregister,uri: `{0}`, rawMessage: `{1}`",
            socketUri,
            event.getRawMessage().encode()));
      }

      event.complete(true); //使用“true”完成`Promise`以启用进一步处理
    });
  }

  private void onSocketRegistered(BridgeEvent event) {
    String socketUri = (event.socket().uri() + "-" + event.socket().headers().get(SEC_WEBSOCKET_KEY)).replaceAll("/",
        ".");
    logger.info(MessageFormat
        .format("A WebSocket was registered,uri: `{0}`, rawMessage: `{1}`", socketUri, event.getRawMessage().encode()));

    if (event.getRawMessage().getString("address").startsWith("auction.") == false || event.getRawMessage()
        .getJsonObject("headers") == null) {
      return;
    }

    String userId = event.getRawMessage().getJsonObject("headers").getString("userId"); // 从headers获取到userId
    String pageId = event.getRawMessage().getJsonObject("headers").getString("pageId"); // 从headers获取到pageId

    if (userId == null || pageId == null) {
      return;
    }

    MessageConsumer<JsonObject> userIdconsumer = vertx.eventBus().consumer("user." + userId, this::userMsgHandler);
    pageIdAndConsumerUserMsgMap.put(pageId, userIdconsumer);

    socketUriAndPageIdMap.put(socketUri, pageId);
    socketUriAndUserIdMap.put(socketUri, userId);

    //再把userId和(socketUri, pageId)放到Map里
    JsonObject jsObj = userIdAndSocketUri_PageRMap.get(userId);
    if (jsObj == null) {
      jsObj = new JsonObject();
    }
    jsObj.put(socketUri, pageId);
    userIdAndSocketUri_PageRMap.put(userId, jsObj);
  }

  private void onSocketClosed(BridgeEvent event) {
    String socketUri = (event.socket().uri() + "-" + event.socket().headers().get(SEC_WEBSOCKET_KEY)).replaceAll("/",
        ".");
    logger.info(MessageFormat.format("A WebSocket was closed,uri: `{0}`", socketUri));

    String pageId = socketUriAndPageIdMap.remove(socketUri);
    String userId = socketUriAndUserIdMap.remove(socketUri);
    if (userId == null) {
      logger.warn(MessageFormat.format("没找到此 socketUri: `{0}` 下的 userId: `{1}`", socketUri, userId));

      return;
    }

    JsonObject jsObj       = userIdAndSocketUri_PageRMap.get(userId);
    String     storePageId = (String) jsObj.remove(socketUri);
    if (pageId.equals(storePageId) == false) {
      logger.warn(MessageFormat.format("没找到此 socketUri: `{0}` 下的 pageId: `{1}`", socketUri, storePageId));
    } else {
      userIdAndSocketUri_PageRMap.put(userId, jsObj);
      logger.info(MessageFormat.format("移除 绑定到此socketUri: `{0}` 下的 pageId: `{1}`", socketUri, storePageId));

      MessageConsumer<JsonObject> consumerUserMsg = pageIdAndConsumerUserMsgMap.remove(pageId);
      consumerUserMsg.unregister();
      logger.info(MessageFormat.format("移除 绑定到此pageId: `{0}` 下的 consumer: `{1}`", pageId, consumerUserMsg));

      if (jsObj.size() == 0) {
        userIdAndSocketUri_PageRMap.remove(userId);
        logger.info(MessageFormat.format("移除 AsyncMap: `{0}` 下的 `{1}`", userIdAndSocketUri_PageRMap.getName(), userId));
      }
    }
  }

  private void userMsgHandler(Message<JsonObject> message) {
    String userId = message.headers().get("userId");

    String rMsg = MessageFormat.format("收到了: address: `{0}`, body: `{1}`, rawMessage: `{3}`",
        message.address(),
        message.body(),
        dumpMessage(message));
    logger.info(rMsg);

    //查询出这个userId下的所关联的所有的pageId,然后转发过去.

    JsonObject jsSocketAndPageId = userIdAndSocketUri_PageRMap.get(userId);
    jsSocketAndPageId.fieldNames().forEach(key -> {
      //if (key.equals(socketUri) == false) { //过滤掉发送方的
      String pageId2 = jsSocketAndPageId.getString(key);
      vertx.eventBus().send(pageId2, message.body(), new DeliveryOptions().setHeaders(message.headers()));
      //}
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
    AuctionRepository repository = new AuctionRepository(vertx);
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

  @SuppressWarnings("rawtypes")
  private String dumpMessage(Message message) {
    return MessageFormat.format("address:`{0}` replyAddress:`{1}` headers:`{2}` isSend:`{3}` body:`{4}`",
        message.address(),
        message.replyAddress(),
        message.headers(),
        message.isSend(),
        message.body());
  }
}
