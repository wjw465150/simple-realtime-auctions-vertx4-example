/*
 * author: @wjw
 * date:   2023年2月7日 上午11:36:27
 * note: 
 */
package com.github.wjw.realtimeauctions;

import io.vertx.core.Future;
import io.vertx.core.Promise;

public class AuctionValidator {

  private final AuctionRepository repository;

  public AuctionValidator(AuctionRepository repository) {
    this.repository = repository;
  }

  /**
   * 校验竞拍是否合规
   *
   * @param auction the auction
   * @return the future<Boolean>
   */
  public Future<Boolean> validate(Auction auction) {
    Promise<Boolean> promise = Promise.promise();

    repository.getById(auction.getId()).onSuccess(it -> {
      if (it.isPresent()) {
        Auction serverAuction = it.get();  //服务端的Auction
        if (serverAuction.getPrice().compareTo(auction.getPrice()) == -1) {
          promise.complete(true);
        } else {
          promise.fail(new AuctionNotFoundException(auction.getId()));
        }
      } else {
        promise.fail(new AuctionValidException(auction.getId()));
      }
    }).onFailure(ex -> {
      promise.fail(ex);
    });

    return promise.future();
  }
}
