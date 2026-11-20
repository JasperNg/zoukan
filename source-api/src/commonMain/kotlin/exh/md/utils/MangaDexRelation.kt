package exh.md.utils

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.sy.SYMR
import tachiyomi.i18n.sy.relation_adapted_from
import tachiyomi.i18n.sy.relation_alternate_story
import tachiyomi.i18n.sy.relation_alternate_version
import tachiyomi.i18n.sy.relation_based_on
import tachiyomi.i18n.sy.relation_colored
import tachiyomi.i18n.sy.relation_doujinshi
import tachiyomi.i18n.sy.relation_main_story
import tachiyomi.i18n.sy.relation_monochrome
import tachiyomi.i18n.sy.relation_prequel
import tachiyomi.i18n.sy.relation_preserialization
import tachiyomi.i18n.sy.relation_same_franchise
import tachiyomi.i18n.sy.relation_sequel
import tachiyomi.i18n.sy.relation_serialization
import tachiyomi.i18n.sy.relation_shared_universe
import tachiyomi.i18n.sy.relation_side_story
import tachiyomi.i18n.sy.relation_similar
import tachiyomi.i18n.sy.relation_spin_off

enum class MangaDexRelation(val res: StringResource, val mdString: String?) {
    SIMILAR(SYMR.strings.relation_similar, null),
    MONOCHROME(SYMR.strings.relation_monochrome, "monochrome"),
    MAIN_STORY(SYMR.strings.relation_main_story, "main_story"),
    ADAPTED_FROM(SYMR.strings.relation_adapted_from, "adapted_from"),
    BASED_ON(SYMR.strings.relation_based_on, "based_on"),
    PREQUEL(SYMR.strings.relation_prequel, "prequel"),
    SIDE_STORY(SYMR.strings.relation_side_story, "side_story"),
    DOUJINSHI(SYMR.strings.relation_doujinshi, "doujinshi"),
    SAME_FRANCHISE(SYMR.strings.relation_same_franchise, "same_franchise"),
    SHARED_UNIVERSE(SYMR.strings.relation_shared_universe, "shared_universe"),
    SEQUEL(SYMR.strings.relation_sequel, "sequel"),
    SPIN_OFF(SYMR.strings.relation_spin_off, "spin_off"),
    ALTERNATE_STORY(SYMR.strings.relation_alternate_story, "alternate_story"),
    PRESERIALIZATION(SYMR.strings.relation_preserialization, "preserialization"),
    COLORED(SYMR.strings.relation_colored, "colored"),
    SERIALIZATION(SYMR.strings.relation_serialization, "serialization"),
    ALTERNATE_VERSION(SYMR.strings.relation_alternate_version, "alternate_version"),
    ;

    companion object {
        fun fromDex(mdString: String) = entries.find { it.mdString == mdString }
    }
}
