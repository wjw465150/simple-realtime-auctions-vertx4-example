/*
 * author: @wjw
 * date:   2023年2月7日 上午11:42:00
 * note: 
 */
package com.github.wjw.realtimeauctions;

import java.math.BigDecimal;
import java.util.Optional;

import org.redisson.Redisson;
import org.redisson.api.RMap;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

public class AuctionRepository {
  private Vertx  vertx;
  private String auctionIdMapNamePrefix;

  public AuctionRepository(Vertx vertx) {
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

    RMap<String, String> auctionIdRMap = vertx.getOrCreateContext()
        .<Redisson> get("redis")
        .getMap(auctionIdMapNamePrefix + auctionId);
    if (auctionIdRMap.size() == 0) {
      promise.complete(Optional.empty());
    } else {
      Auction auction = new Auction(auctionIdRMap.get("id"), new BigDecimal(auctionIdRMap.get("price")));
      promise.complete(Optional.of(auction));
    }

    return promise.future();
  }

  /**
   * 把auction保存到server
   *
   * @param auction the auction
   */
  public void save(Auction auction) {
    RMap<String, String> auctionIdRMap = vertx.getOrCreateContext()
        .<Redisson> get("redis")
        .getMap(auctionIdMapNamePrefix + auction.getId());
    auctionIdRMap.put("id", auction.getId());
    auctionIdRMap.put("price", auction.getPrice().toString());
  }

}
