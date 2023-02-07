/*
 * author: @wjw
 * date:   2023年2月3日 上午9:35:17
 * note: 
 */
package com.github.wjw.realtimeauctions;

import java.math.BigDecimal;

import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;

public class AuctionHandler {

  private final AuctionRepository repository;
  private final AuctionValidator  validator;

  public AuctionHandler(AuctionRepository repository, AuctionValidator validator) {
    this.repository = repository;
    this.validator = validator;
  }

  /**
   * Handle get auction. 查询当前竞价
   *
   * @param context the RoutingContext
   */
  public void handleGetAuction(RoutingContext context) {
    String auctionId = context.request().getParam("id");

    this.repository.getById(auctionId).onSuccess(auction -> {
      if (auction.isPresent()) {
        context.response()
            .putHeader("content-type", "application/json")
            .setStatusCode(200)
            .end(Json.encodePrettily(auction.get()));
      } else {
        context.response()
            .putHeader("content-type", "application/json")
            .setStatusCode(404)
            .end();
      }
    });
  }

  /**
   * Handle change auction price. 更新竞价
   *
   * @param context the RoutingContext
   */
  public void handleChangeAuctionPrice(RoutingContext context) {
    String  auctionId      = context.request().getParam("id");
    Auction auctionRequest = new Auction(
        auctionId,
        new BigDecimal(context.body().asJsonObject().getString("price"))
    );

    validator.validate(auctionRequest).onSuccess(it -> {
      this.repository.save(auctionRequest); // TODO: 这里是否要改成等待save成功?
      
      //@wjw_note: 先向eventBus发布地址为`auction.{auction_id}`的消息,消息内容是接受到的客户端传来的主体数据
      context.vertx().eventBus().publish("auction." + auctionId, context.body().asJsonObject());

      context.response()
          .setStatusCode(200)
          .end();

    }).onFailure(ex -> {
      context.response()
          .setStatusCode(422)
          .end();
    });
  }

  /**
   * Inits the auction in shared data.
   *
   * @param context the RoutingContext
   */
  public void initAuctionInSharedData(RoutingContext context) {
    String auctionId = context.request().getParam("id");

    this.repository.getById(auctionId).onSuccess(auction -> {
      if (!auction.isPresent()) {
        this.repository.save(new Auction(auctionId));
      }

      context.next(); //@wjw_note: 告诉路由器将此上下文路由到下一个匹配路由,此处是`this.handleGetAuction`方法
    });
  }
}
