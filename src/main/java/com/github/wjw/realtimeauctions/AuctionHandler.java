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

  private final AuctionRepositoryRedis repositoryRedis;
  private final AuctionRepositoryMongo repositoryMongo;
  private final AuctionValidator  validator;

  public AuctionHandler(AuctionRepositoryRedis repositoryRedis, AuctionRepositoryMongo repositoryMongo,AuctionValidator validator) {
    this.repositoryRedis = repositoryRedis;
    this.repositoryMongo = repositoryMongo;
    this.validator = validator;
  }

  /**
   * Handle get auction. 查询当前竞价
   *
   * @param context the RoutingContext
   */
  public void handleGetAuction(RoutingContext context) {
    String auctionId = context.request().getParam("id");

    this.repositoryRedis.getById(auctionId).onSuccess(auctionRedis -> {
      if (auctionRedis.isPresent()) {
        context.response()
            .putHeader("content-type", "application/json")
            .setStatusCode(200)
            .end(Json.encodePrettily(auctionRedis.get()));
      } else {
        this.repositoryMongo.getById(auctionId).onSuccess(auctionMongo -> {
          if (auctionMongo.isPresent()) {
            this.repositoryRedis.save(auctionMongo.get());  //先保存到redis缓存里
            
            context.response()
            .putHeader("content-type", "application/json")
            .setStatusCode(200)
            .end(Json.encodePrettily(auctionMongo.get()));
          } else {
            context.response()
            .putHeader("content-type", "application/json")
            .setStatusCode(404)
            .end();
          }
        });
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
      this.repositoryMongo.save(auctionRequest); 
      this.repositoryRedis.save(auctionRequest); // 更新redis缓存
      
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

    this.repositoryRedis.getById(auctionId).onSuccess(auction -> {
      if (!auction.isPresent()) {
        Auction auctionObj = new Auction(auctionId);
        this.repositoryRedis.save(auctionObj);
        this.repositoryMongo.save(auctionObj); 
      }

      context.next(); //@wjw_note: 告诉路由器将此上下文路由到下一个匹配路由,此处是`this.handleGetAuction`方法
    });
  }
}
