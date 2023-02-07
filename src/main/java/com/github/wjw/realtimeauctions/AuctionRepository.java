/*
 * author: @wjw
 * date:   2023年2月7日 上午11:42:00
 * note: 
 */
package com.github.wjw.realtimeauctions;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.SharedData;

public class AuctionRepository {

  private SharedData sharedData;

  public AuctionRepository(SharedData sharedData) {
    this.sharedData = sharedData;
  }

  /**
   * 从Server里获取Auction
   *
   * @param auctionId the auction id
   * @return {@code Future<Optional<Auction>>}
   */
  public Future<Optional<Auction>> getById(String auctionId) {
    Promise<Optional<Auction>>       promise = Promise.promise();
    Future<AsyncMap<String, String>> fFuture = this.sharedData.getAsyncMap(auctionId);
    fFuture.onSuccess(it -> {
      it.size().onSuccess(size -> {
        if (size == 0) {
          promise.complete(Optional.empty());
        } else {
          it.entries().map(this::convertToAuction).onSuccess(auction -> {
            promise.complete(Optional.of(auction));
          });
        }
      });
    }).onFailure(ex -> {
      promise.fail(ex);
    });
    return promise.future();
  }

  /**
   * 把auction保存到server
   *
   * @param auction the auction
   */
  public void save(Auction auction) {
    Future<AsyncMap<String, String>> future = this.sharedData.getAsyncMap(auction.getId());
    future.onSuccess(it -> {
      it.put("id", auction.getId());
      it.put("price", auction.getPrice().toString());
    });
  }

  
  /**
   * Convert Map to auction.
   *
   * @param the Map hold the auction data
   * @return the auction
   */
  private Auction convertToAuction(Map<String, String> auction) {
    return new Auction(
        auction.get("id"),
        new BigDecimal(auction.get("price"))
    );
  }
}
