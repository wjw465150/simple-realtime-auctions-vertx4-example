package com.github.wjw.realtimeauctions;

public class AuctionValidException extends RuntimeException {
    public AuctionValidException(String auctionId) {
        super("Auction valid error: " + auctionId);
    }
}
