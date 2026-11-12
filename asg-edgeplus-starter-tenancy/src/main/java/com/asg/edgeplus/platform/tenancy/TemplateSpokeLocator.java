package com.asg.edgeplus.platform.tenancy;

import com.asg.edgeplus.platform.domain.primitives.TenantId;

/**
 * Derives a firm's database name from a template such as {@code asg_spoke_{tenant}}, where
 * {@code {tenant}} becomes the firm id as 32 hex digits without hyphens.
 */
public final class TemplateSpokeLocator implements SpokeLocator {

  private static final String PLACEHOLDER = "{tenant}";

  private final String template;

  public TemplateSpokeLocator(String template) {
    if (template == null || template.isBlank()) {
      throw new IllegalArgumentException(
          "asg.platform.tenancy.database-name-template is required unless a SpokeLocator bean is"
              + " declared");
    }
    if (!template.contains(PLACEHOLDER)) {
      throw new IllegalArgumentException(
          "database-name-template must contain " + PLACEHOLDER
              + " or every firm would share one database: " + template);
    }
    this.template = template;
  }

  @Override
  public String databaseName(TenantId tenant) {
    String name = template.replace(PLACEHOLDER, tenant.value().toString().replace("-", ""));
    if (!TenancyProperties.SAFE_DB_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("template produced an unsafe database name: " + name);
    }
    return name;
  }
}
