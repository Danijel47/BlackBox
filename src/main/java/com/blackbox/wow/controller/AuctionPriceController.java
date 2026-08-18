package com.blackbox.wow.controller;

import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardAuctionService.PriceResult;
import com.blackbox.wow.blizzard.BlizzardItemService;
import com.blackbox.wow.blizzard.BlizzardItemService.ItemRef;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auction")
@ConditionalOnProperty(name = "wow.auction-api.enabled", havingValue = "true")
public class AuctionPriceController {

    private final BlizzardAuctionService auctionService;
    private final BlizzardItemService itemService;

    public AuctionPriceController(BlizzardAuctionService auctionService, BlizzardItemService itemService) {
        this.auctionService = auctionService;
        this.itemService = itemService;
    }

    @GetMapping("/price")
    public ResponseEntity<AuctionResponse> getPrice(
            @RequestParam(required = false) Long itemId,
            @RequestParam(required = false) String itemName,
            @RequestParam(required = false) String realm,
            @RequestParam(required = false) Long connectedRealmId,
            @RequestParam(required = false) Long auctionHouseId
    ) {
        if (itemId == null && (itemName == null || itemName.isBlank())) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Provide either itemId or itemName"));
        }

        ItemRef item = resolveItem(itemId, itemName);
        if (item == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Item not found"));
        }

        if (auctionHouseId != null && connectedRealmId != null) {
            PriceResult result = auctionService.getAuctionHouseAverage(connectedRealmId, auctionHouseId, item.id());
            return toResponse(result, "auctionHouse", item);
        }

        if (realm != null && !realm.isBlank()) {
            PriceResult result = auctionService.getRealmAverage(realm, item.id());
            return toResponse(result, "realm", item);
        }

        if (isWowToken(item.name())) {
            PriceResult result = auctionService.getWowTokenPrice();
            return toResponse(result, "wowToken", item);
        }

        PriceResult result = auctionService.getRegionAverage(item.id());
        return toResponse(result, "region", item);
    }

    @GetMapping("/resolve-item")
    public ResponseEntity<AuctionResponse> resolveItem(@RequestParam String name) {
        ItemRef item = itemService.findByName(name);
        if (item == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Item not found"));
        }

        return ResponseEntity.ok(new ItemResponse(item.id(), item.name()));
    }

    private ItemRef resolveItem(Long itemId, String itemName) {
        if (itemId != null) {
            return itemService.getById(itemId);
        }
        return itemService.findByName(itemName);
    }

    private ResponseEntity<AuctionResponse> toResponse(PriceResult result, String scope, ItemRef item) {
        if (!result.available()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new PricingErrorResponse("No pricing data", scope, item.id(), item.name()));
        }
        return ResponseEntity.ok(new PriceResponse(
                scope,
                item.id(),
                item.name(),
                result.avgCopper(),
                result.gold(),
                result.silver(),
                result.copper()
        ));
    }

    private static boolean isWowToken(String name) {
        return name != null && name.trim().equalsIgnoreCase("wow token");
    }

    public sealed interface AuctionResponse permits ErrorResponse, PricingErrorResponse, ItemResponse, PriceResponse {
    }

    public record ErrorResponse(String error) implements AuctionResponse {
    }

    public record PricingErrorResponse(String error, String scope, long itemId, String itemName)
            implements AuctionResponse {
    }

    public record ItemResponse(long itemId, String itemName) implements AuctionResponse {
    }

    public record PriceResponse(
            String scope,
            long itemId,
            String itemName,
            long avgCopper,
            long gold,
            long silver,
            long copper
    ) implements AuctionResponse {
    }
}
