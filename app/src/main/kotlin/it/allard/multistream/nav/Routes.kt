package it.allard.multistream.nav

import android.util.Base64
import it.allard.multistream.core.model.TitleKey

/** Title keys contain ':' and spaces, so they are Base64url-encoded for use as a nav argument. */
fun encodeTitleKey(key: TitleKey): String =
    Base64.encodeToString(key.serialize().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

fun decodeTitleKey(arg: String): TitleKey =
    TitleKey.parse(String(Base64.decode(arg, Base64.URL_SAFE)))
