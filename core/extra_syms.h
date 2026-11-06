#ifndef IC_EXTRA_SYMS_H
#define IC_EXTRA_SYMS_H

/*
 * Per-build fake-cred objects that the old 66-entry anchor tables never
 * carried.  They are data symbols (not code anchors), so they cannot be
 * derived from a table; they are extracted from the image of each build we
 * have locally, which makes those builds work from the built-in table alone
 * (no external ic-syms.txt, i.e. the same result as "解析偏移").
 *
 * Keyed by the anchor table's name.  Missing builds simply fall back to the
 * external table.
 */
struct ic_extra_syms {
    const char *table;
    unsigned long long root_user;
    unsigned long long init_ucounts;
    unsigned long long init_groups;
};

static const struct ic_extra_syms ic_extra_syms_tab[] = {
    /* 6.1.145-android14-11-maybe-dirty, iQOO 12 16.2.11 (download_kernel.Image) */
    { "iqoo12-16.2.11", 0xffffffc00a121108ULL, 0xffffffc00a1229b8ULL,
      0xffffffc00a122428ULL },
    /* 6.1.145-android14-11-maybe-dirty, PD2307/PD2329 16.2.21.1
     * (iqoo12-16211-kernel.Image; tp=0xffffffc00a3cc1c0 matches the table) */
    { "ic_iqoo12_16211_links", 0xffffffc00a1f11c8ULL, 0xffffffc00a1f2a78ULL,
      0xffffffc00a1f24e8ULL },
};

#endif /* IC_EXTRA_SYMS_H */
