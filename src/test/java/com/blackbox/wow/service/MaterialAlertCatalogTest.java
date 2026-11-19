package com.blackbox.wow.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MaterialAlertCatalogTest {
    @Test void gatheringAndProfessionGroupsIncludeRawGemsAndRareSkinningMaterials() {
        var catalog = new MaterialAlertCatalog();
        assertThat(catalog.group("jewelcrafting")).extracting(MaterialAlertCatalog.Material::id)
                .contains(242607L, 242720L, 242554L, 242722L, 242606L, 242721L, 242553L, 242723L);
        assertThat(catalog.group("skinning")).extracting(MaterialAlertCatalog.Material::id).contains(238530L);
        assertThat(catalog.group("inscription")).extracting(MaterialAlertCatalog.Material::id).contains(245807L, 245808L);
        assertThat(catalog.group("engineering")).extracting(MaterialAlertCatalog.Material::id).doesNotContain(253302L);
        assertThat(catalog.group("mining")).isEqualTo(catalog.group("ores"));
        assertThat(catalog.group("herbalism")).isEqualTo(catalog.group("herbs"));
        assertThat(catalog.group("lw")).isEqualTo(catalog.group("leatherworking"));
        assertThat(catalog.group("fishing")).extracting(MaterialAlertCatalog.Material::id).contains(238383L, 274594L);
        assertThat(catalog.group("cooking")).extracting(MaterialAlertCatalog.Material::id).contains(242639L, 253403L, 238383L);
        catalog.groups().values().forEach(items -> {
            assertThat(items).isNotEmpty();
            assertThat(items).extracting(MaterialAlertCatalog.Material::id).doesNotHaveDuplicates();
        });
    }
}
