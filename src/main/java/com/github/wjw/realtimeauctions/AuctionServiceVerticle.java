/*
 * author: @wjw
 * date:   2023年2月2日 下午6:12:45
 * note: 
 */
package com.github.wjw.realtimeauctions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
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

  public AuctionServiceVerticle() {
    this.logger = LoggerFactory.getLogger(this.getClass());
  }

  @Override
  public void start(Promise<Void> startPromise) throws Exception {
    Router router = Router.router(vertx);

    router.route().failureHandler(errorHandler());  //将故障处理程序附加到路由故障处理程序列表。
    router.route().handler(staticHandler());  //Vert.x-Web 带有一个开箱即用的处理程序，用于提供静态 Web 资源
    router.route().handler(ResponseTimeHandler.create()); //此处理程序设置标头`x-response-time`响应标头，其中包含从收到请求到写入响应标头的时间，以毫秒为单位
    router.route().handler(LoggerHandler.create()); //Vert.x-Web 包含一个处理程序`LoggerHandler`，您可以使用它来记录 HTTP 请求。 您应该在任何可能使 `RoutingContext` 失败的处理程序之前安装此处理程序

    router.route("/eventbus/*").subRouter(eventBusHandler());
    router.route("/api/*").subRouter(auctionApiRouter());

    vertx.createHttpServer()
        .requestHandler(router)
        .listen(PORT)
        .onSuccess(server -> {
          logger.info("Realtime Auctions Server start OK! listen port: " + server.actualPort());
          startPromise.complete();
        })
        .onFailure(throwable -> startPromise.fail(throwable));

  }

  private Router eventBusHandler() {
    SockJSBridgeOptions options = new SockJSBridgeOptions();
    options.addOutboundPermitted(new PermittedOptions().setAddressRegex("auction\\.[0-9]+"));

    SockJSHandler sockJSHandler = SockJSHandler.create(vertx);

    return sockJSHandler.bridge(options, event -> {
      if (event.type() == BridgeEventType.SOCKET_CREATED) {
        logger.info("A socket was created");
      }
      event.complete(true);
    });
  }

  /**
   * Auction api router.
   *
   * @return the router
   */
  private Router auctionApiRouter() {
    AuctionRepository repository = new AuctionRepository(vertx.sharedData());
    AuctionValidator  validator  = new AuctionValidator(repository);
    AuctionHandler    handler    = new AuctionHandler(repository, validator);

    Router router = Router.router(vertx); 
    router.route().handler(BodyHandler.create());  //`BodyHandler` 允许您检索请求正文、限制正文大小和处理文件上传。

    router.route().consumes("application/json");
    router.route().produces("application/json");

    router.route("/auctions/:id").handler(handler::initAuctionInSharedData);
    router.get("/auctions/:id").handler(handler::handleGetAuction);
    router.patch("/auctions/:id").handler(handler::handleChangeAuctionPrice);

    return router;
  }

  private ErrorHandler errorHandler() {
    return ErrorHandler.create(vertx, true);
  }

  private StaticHandler staticHandler() {
    return StaticHandler.create()
        .setCachingEnabled(false);
  }
}
