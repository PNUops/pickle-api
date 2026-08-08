package kr.ac.pusan.pickle.inventory;

/** Contract schema {@code CatalogStatus}; catalog entry updates create a new version row and DISABLE the old one. */
public enum CatalogStatus {
    ACTIVE,
    DISABLED
}
