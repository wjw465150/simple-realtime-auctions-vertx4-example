/*
 * author: @wjw
 * date:   2023年2月7日 上午11:42:00
 * note: 
 */
package com.github.wjw.realtimeauctions;

import java.math.BigDecimal;
import java.util.Optional;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mongo.MongoClient;
import io.vertx.ext.mongo.MongoClientUpdateResult;
import io.vertx.ext.mongo.UpdateOptions;

public class AuctionRepositoryMongo {
  private Vertx  vertx;
  private String auctionIdMapNamePrefix;

  public AuctionRepositoryMongo(Vertx vertx) {
    this.vertx = vertx;
    auctionIdMapNamePrefix = vertx.getOrCreateContext().config().getString("profile") + "_auction_";
  }

  /**
   * 从Server里获取Auction
   *
   * @param auctionId the auction id
   * @return {@code Future<Optional<Auction>>}
   */
  public Future<Optional<Auction>> getById(String auctionId) {
    Promise<Optional<Auction>> promise = Promise.promise();

    MongoClient mongoClient = vertx.getOrCreateContext().<MongoClient> get("mongo");

    mongoClient.findOne(auctionIdMapNamePrefix + auctionId, null, new JsonObject().put("id", auctionId), res -> {
      JsonObject jsonAuction = res.result();
      if (jsonAuction == null || jsonAuction.isEmpty()) {
        promise.complete(Optional.empty());
      } else {
        Auction auction = new Auction(jsonAuction.getString("id"), new BigDecimal(jsonAuction.getLong("price")));
        promise.complete(Optional.of(auction));
      }
    });

    return promise.future();
  }

  /**
   * 把auction保存到server
   *
   * @param auction the auction
   */
  public void save(Auction auction) {
    MongoClient mongoClient = vertx.getOrCreateContext().<MongoClient> get("mongo");

    JsonObject jsonAuction = new JsonObject()
        .put("id", auction.getId())
        .put("price", auction.getPrice().longValue());
    
    // MongoDB 默认写操作级别是 WriteOption.ACKNOWLEDGED
    JsonObject query = new JsonObject().put("id", auction.getId());
    JsonObject update = new JsonObject().put("$set",jsonAuction);
    mongoClient.updateCollectionWithOptions(auctionIdMapNamePrefix + auction.getId(), query, update, new UpdateOptions(true));
  }

}
