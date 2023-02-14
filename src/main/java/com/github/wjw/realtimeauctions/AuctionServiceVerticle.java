/*
 * author: @wjw
 * date:   2023年2月2日 下午6:12:45
 * note: 
 */
package com.github.wjw.realtimeauctions;

import java.text.MessageFormat;
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
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;
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
  public final static Integer PORT = 9090;
  private Logger              logger;
  private String              profile;

  private Map<String, String> socketUriAndPageIdMap = new HashMap<>();
  private Map<String, String> pageIdAndSocketUriMap = new HashMap<>();

  private Map<String, String> socketUriAndUserIdMap  = new HashMap<>();
  private static final String USER_AND_PAGE_MAP_NAME = "UserAndPage";
  //key是userId, value是一个JSON对象(里面key是socketUri,value是pageId)
  private AsyncMap<String, JsonObject> userAndSocketUri_PageMap;

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

      vertx.sharedData().<String, JsonObject> getAsyncMap(USER_AND_PAGE_MAP_NAME).onSuccess(itMap -> {
        userAndSocketUri_PageMap = itMap;

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
      }).onFailure(throwable -> startPromise.fail(throwable));

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
    bridgeOptions.addOutboundPermitted(new PermittedOptions().setAddressRegex("auction\\..+")); //"auction\\.[0-9]+"
    bridgeOptions.addInboundPermitted(new PermittedOptions().setAddressRegex("auction\\..+"));

    bridgeOptions.addOutboundPermitted(new PermittedOptions().setAddressRegex("user\\..+"));
    bridgeOptions.addInboundPermitted(new PermittedOptions().setAddressRegex("user\\..+"));

    bridgeOptions.addOutboundPermitted(new PermittedOptions().setAddressRegex("page\\..+"));
    bridgeOptions.addInboundPermitted(new PermittedOptions().setAddressRegex("page\\..+"));

    SockJSHandlerOptions sockJSHandlerOptions = new SockJSHandlerOptions();
    if (profile.equalsIgnoreCase("dev")) { //如果是开发环境把session超时设置长一些,防止前端在断点调试时候被超时关闭.
      sockJSHandlerOptions.setSessionTimeout(600L * 1000);
    }
    sockJSHandlerOptions.setHeartbeatInterval(20L * 1000);
    sockJSHandlerOptions.setRegisterWriteHandler(false); //@wjw_note: 用了`eventbus bridge`方式就不能再使用`writeHandler`
    SockJSHandler sockJSHandler = SockJSHandler.create(vertx, sockJSHandlerOptions);

    return sockJSHandler.bridge(bridgeOptions, event -> {
      if (event.type() == BridgeEventType.SOCKET_CREATED) {
        logger.info(MessageFormat.format("A WebSocket was created,uri: `{0}`", event.socket().uri()));
      } else if (event.type() == BridgeEventType.SOCKET_CLOSED) {
        logger.info(MessageFormat.format("A WebSocket was closed,uri: `{0}`", event.socket().uri()));

        String socketUri      = event.socket().uri().replaceAll("/", ".");
        String pageId         = socketUriAndPageIdMap.remove(socketUri);
        String storeSocketUri = pageIdAndSocketUriMap.remove(pageId);
        if (storeSocketUri.equals(socketUri) == false) {
          logger.warn(MessageFormat.format("没有找到此 pageId: `{0}` 下的 socketUri: `{1}`", pageId, storeSocketUri));
        }

        String userId = socketUriAndUserIdMap.remove(socketUri);
        if (userId == null) {
          logger.warn(MessageFormat.format("没找到此socketUri: `{0}` 下的 userId: `{1}`", socketUri, userId));
        } else {
          userAndSocketUri_PageMap.get(userId).onComplete(it -> {
            JsonObject jsSocketAndPageId = it.result();
            String     storePageId       = (String) jsSocketAndPageId.remove(socketUri);
            if (storePageId.equals(pageId) == false) {
              logger.warn(MessageFormat.format("没找到此socketUri: `{0}` 下的 pageId: `{1}`", socketUri, storePageId));
            } else {
              logger.info(MessageFormat.format("移除 绑定到此socketUri: `{0}` 下的 pageId: `{1}`", socketUri, storePageId));
            }
          }).andThen(it -> {
            JsonObject jsSocketAndPageId = it.result();
            if(jsSocketAndPageId.size()==0) {
              userAndSocketUri_PageMap.remove(userId);
            }
          });
        }
      } else if (event.type() == BridgeEventType.REGISTERED) {
        logger.info(MessageFormat.format("A WebSocket was registered,uri: `{0}`, rawMessage: `{1}`", event.socket().uri(), event.getRawMessage().encode()));

        if (event.getRawMessage().getString("address").startsWith("auction.") && event.getRawMessage().getJsonObject("headers") != null) {
          String userId = event.getRawMessage().getJsonObject("headers").getString("userId"); // 从headers获取到userId
          String pageId = event.getRawMessage().getJsonObject("headers").getString("pageId"); // 从headers获取到pageId

          String socketUri = event.socket().uri().replaceAll("/", "."); // TODO: 这里如果把传来的userId作为key,当一个用户同时打开多个Page时会覆盖前面打开的!
          if (userId != null && pageId != null) {
            //服务器与客户端的User之间的point-to_point通信,有可能用户打开多个page,通信落在不同的服务器上,所有不能是local的
            MessageConsumer<JsonObject> wsUserIdconsumer = vertx.eventBus().consumer("user." + userId, this::userMsgHandler);

            socketUriAndPageIdMap.put(socketUri, pageId);
            pageIdAndSocketUriMap.put(pageId, socketUri);
            socketUriAndUserIdMap.put(socketUri, userId);

            //再把userId和(socketUri, pageId)放到Map里
            userAndSocketUri_PageMap.get(userId).onComplete(asr -> {
              JsonObject jsObj = asr.result();
              if (jsObj == null) {
                jsObj = new JsonObject();
              }
              jsObj.put(socketUri, pageId);
              userAndSocketUri_PageMap.put(userId, jsObj);
            });
          }
        }
      }

      event.complete(true); //使用“true”完成`Promise`以启用进一步处理
    });
  }

  private void userMsgHandler(Message<JsonObject> message) {
    String userId    = message.headers().get("userId");
    String pageId    = message.headers().get("pageId");
    String socketUri = pageIdAndSocketUriMap.get(pageId);

    String rMsg = MessageFormat.format("收到了: address: `{0}`, body: `{1}`, socket uri: `{2}`, rawMessage: `{3}`",
        message.address(),
        message.body(),
        socketUri,
        dumpMessage(message));
    logger.info(rMsg);

    //查询出这个userId下的所关联的所有的pageId,然后转发过去.
    userAndSocketUri_PageMap.get(userId).onComplete(it -> {
      JsonObject jsSocketAndPageId = it.result();
      jsSocketAndPageId.fieldNames().forEach(key -> {
        //if (key.equals(socketUri) == false) { //过滤掉发送方的
        String pageId2 = jsSocketAndPageId.getString(key);
        vertx.eventBus().send(pageId2, message.body(), new DeliveryOptions().setHeaders(message.headers()));
        //}
      });

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

  private String dumpMessage(Message message) {
    return MessageFormat.format("address:`{0}` replyAddress:`{1}` headers:`{2}` isSend:`{3}` body:`{4}`",
        message.address(),
        message.replyAddress(),
        message.headers(),
        message.isSend(),
        message.body());
  }
}
