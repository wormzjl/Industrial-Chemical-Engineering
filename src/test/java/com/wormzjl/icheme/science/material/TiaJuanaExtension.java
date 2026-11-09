package com.wormzjl.icheme.science.material;

/** Runs each test method, parameterised ones included, in {@link TiaJuanaTestCatalog} (test-scoped, never published). */
public final class TiaJuanaExtension extends CatalogScopedExtension {
    public TiaJuanaExtension() { super(TiaJuanaTestCatalog::catalog, TiaJuanaTestCatalog.PACKAGE_ID, true); }
}
