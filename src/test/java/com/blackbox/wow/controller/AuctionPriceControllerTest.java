package com.blackbox.wow.controller;

import com.blackbox.wow.blizzard.BlizzardAuctionService;
import com.blackbox.wow.blizzard.BlizzardItemService;
import com.blackbox.wow.controller.AuctionPriceController.ErrorResponse;
import com.blackbox.wow.controller.AuctionPriceController.ItemResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionPriceControllerTest {

    @Mock
    private BlizzardAuctionService auctionService;

    @Mock
    private BlizzardItemService itemService;

    @Test
    void doesNotReflectUnknownItemNameInErrorResponse() {
        String unsafeName = "<script>alert('xss')</script>";
        when(itemService.findByName(unsafeName)).thenReturn(null);

        var response = controller().resolveItem(unsafeName);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(new ErrorResponse("Item not found"));
        assertThat(String.valueOf(response.getBody())).doesNotContain(unsafeName);
    }

    @Test
    void returnsTypedResolvedItemResponse() {
        when(itemService.findByName("refulgent copper ore"))
                .thenReturn(new BlizzardItemService.ItemRef(237359L, "Refulgent Copper Ore"));

        var response = controller().resolveItem("refulgent copper ore");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new ItemResponse(237359L, "Refulgent Copper Ore"));
    }

    private AuctionPriceController controller() {
        return new AuctionPriceController(auctionService, itemService);
    }
}
