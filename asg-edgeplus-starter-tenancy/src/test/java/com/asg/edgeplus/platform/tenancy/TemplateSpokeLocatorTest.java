package com.asg.edgeplus.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.asg.edgeplus.platform.domain.primitives.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemplateSpokeLocatorTest {

  private static final TenantId FIRM =
      new TenantId(UUID.fromString("11111111-2222-3333-4444-555555555555"));

  @Test
  void substitutesTheTenantAsHexWithoutHyphens() {
    assertThat(new TemplateSpokeLocator("asg_spoke_{tenant}").databaseName(FIRM))
        .isEqualTo("asg_spoke_11111111222233334444555555555555");
  }

  @Test
  void givesEachFirmItsOwnDatabase() {
    TemplateSpokeLocator locator = new TemplateSpokeLocator("asg_spoke_{tenant}");
    assertThat(locator.databaseName(FIRM)).isNotEqualTo(locator.databaseName(TenantId.STANDARD));
  }

  @Test
  void aTemplateWithoutThePlaceholderWouldGiveEveryFirmTheSameDatabaseAndIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TemplateSpokeLocator("asg_spoke_shared"))
        .withMessageContaining("{tenant}");
  }

  @Test
  void aBlankTemplateIsRefused() {
    assertThatIllegalArgumentException().isThrownBy(() -> new TemplateSpokeLocator(" "));
    assertThatIllegalArgumentException().isThrownBy(() -> new TemplateSpokeLocator(null));
  }

  @Test
  void aResultOutsideTheSpokeNamespaceIsRefusedAtUse() {
    TemplateSpokeLocator locator = new TemplateSpokeLocator("postgres_{tenant}");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> locator.databaseName(FIRM))
        .withMessageContaining("unsafe");
  }

  @Test
  void aResultTooLongForTheSpokeNamespaceIsRefusedAtUse() {
    TemplateSpokeLocator locator = new TemplateSpokeLocator("asg_spoke_abcdefgh_{tenant}");
    assertThatIllegalArgumentException().isThrownBy(() -> locator.databaseName(FIRM));
  }
}
