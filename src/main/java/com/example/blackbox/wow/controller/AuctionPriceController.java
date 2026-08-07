package com.example.blackbox.wow.controller;

import com.example.blackbox.wow.blizzard.BlizzardAuctionService;
import com.example.blackbox.wow.blizzard.BlizzardAuctionService.PriceResult;
import com.example.blackbox.wow.blizzard.BlizzardItemService;
import com.example.blackbox.wow.blizzard.BlizzardItemService.ItemRef;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auction")
public class AuctionPriceController {

    private final BlizzardAuctionService auctionService;
    private final BlizzardItemService itemService;

    public AuctionPriceController(BlizzardAuctionService auctionService, BlizzardItemService itemService) {
        this.auctionService = auctionService;
        this.itemService = itemService;
    }

    @GetMapping("/price")
    public ResponseEntity<?> getPrice(
            @RequestParam(required = false) Long itemId,
            @RequestParam(required = false) String itemName,
            @RequestParam(required = false) String realm,
            @RequestParam(required = false) Long connectedRealmId,
            @RequestParam(required = false) Long auctionHouseId
    ) {
        if (itemId == null && (itemName == null || itemName.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Provide either itemId or itemName"));
        }

        ItemRef item = resolveItem(itemId, itemName);
        if (item == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Item not found", "itemName", itemName));
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
    public ResponseEntity<?> resolveItem(@RequestParam String name) {
        ItemRef item = itemService.findByName(name);
        if (item == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Item not found", "itemName", name));
        }

        return ResponseEntity.ok(Map.of(
                "itemId", item.id(),
                "itemName", item.name()
        ));
    }

    private ItemRef resolveItem(Long itemId, String itemName) {
        if (itemId != null) {
            return itemService.getById(itemId);
        }
        return itemService.findByName(itemName);
    }

    private ResponseEntity<?> toResponse(PriceResult result, String scope, ItemRef item) {
        if (!result.available()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "error", "No pricing data",
                            "scope", scope,
                            "itemId", item.id(),
                            "itemName", item.name()
                    ));
        }
        return ResponseEntity.ok(Map.of(
                "scope", scope,
                "itemId", item.id(),
                "itemName", item.name(),
                "avgCopper", result.avgCopper(),
                "gold", result.gold(),
                "silver", result.silver(),
                "copper", result.copper()
        ));
    }

    private static boolean isWowToken(String name) {
        return name != null && name.trim().equalsIgnoreCase("wow token");
    }
}
