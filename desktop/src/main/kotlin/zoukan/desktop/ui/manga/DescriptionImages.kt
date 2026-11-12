package zoukan.desktop.ui.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import eu.kanade.domain.ui.UiPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.ExternalLinks
import zoukan.desktop.compat.localized
import zoukan.desktop.ui.common.Images
import zoukan.desktop.ui.common.Toasts
import zoukan.desktop.ui.manga.DescriptionImages.Part
import zoukan.i18n.desktop.DMR
import kotlin.math.roundToInt

// ZOUKAN -->

/**
 * The images written in an entry's description. Android renders the description as Markdown and
 * loads its images while Appearance > "Render images in manga descriptions" is on; off, an image
 * is a link to it. The description here is plain text, so this reads only the images out of it:
 * Markdown's `![alt](address "title")`.
 */
internal object DescriptionImages {
    /** A piece of a description: text as it is written, or an image. */
    sealed interface Part {
        data class Text(val text: String) : Part

        /**
         * [label] stands for the image where it is not shown, as Android's: its title, or else its
         * alt text. [source] is the Markdown it was written as.
         */
        data class Image(val url: String, val label: String, val source: String) : Part
    }

    /** No image is written longer than this, so a description that only looks full of them reads fast. */
    private const val MAX_SOURCE_LENGTH = 4096

    /**
     * [description] in pieces, in order. Only an image with a web address counts: descriptions
     * come from sources, so nothing else is fetched or opened (see [ExternalLinks]), and anything
     * else stays text.
     */
    fun parse(description: String): List<Part> {
        val parts = mutableListOf<Part>()
        var textStart = 0
        var at = description.indexOf("![")
        while (at >= 0) {
            // "\!" is an exclamation mark, not the start of an image
            val image = if (at > 0 && description[at - 1] == '\\') {
                null
            } else {
                imageAt(description.substring(at, minOf(description.length, at + MAX_SOURCE_LENGTH)))
            }
            if (image == null) {
                at = description.indexOf("![", at + 2)
                continue
            }
            if (at > textStart) parts += Part.Text(description.substring(textStart, at))
            parts += image
            textStart = at + image.source.length
            at = description.indexOf("![", textStart)
        }
        if (textStart < description.length) parts += Part.Text(description.substring(textStart))
        return parts
    }

    /**
     * The size an image of [size] shows at where [maxWidth] is all the width there is, as
     * Android's Markdown renderer: its own size, scaled down to that width when it is wider.
     */
    fun fit(size: IntSize, maxWidth: Int): IntSize = when {
        size.width <= maxWidth -> size
        else -> IntSize(maxWidth, (size.height.toLong() * maxWidth / size.width).toInt().coerceAtLeast(1))
    }

    /**
     * [parts] as the description's text: while [loadImages] is on, each image is one inline slot
     * marked with its address; off, the image icon and its label, a link to the image. One slot
     * per image either way, in order, and nothing here knows which images have failed: an image
     * keeps its slot however it shows, so the slots after it are never rebuilt.
     */
    fun annotate(parts: List<Part>, loadImages: Boolean, linkColor: Color): AnnotatedString = buildAnnotatedString {
        parts.forEach { part ->
            when (part) {
                is Part.Text -> append(part.text)
                is Part.Image ->
                    if (loadImages) {
                        // Copying the description copies the image as it was written
                        appendInlineContent(part.url, part.source)
                    } else {
                        withLink(imageLink(part.url, linkColor)) {
                            appendInlineContent(IMAGE_ICON)
                            append(part.label)
                        }
                    }
            }
        }
    }

    /** The image [text] starts with ("![" is its start), or null when it is not one with a web address. */
    private fun imageAt(text: String): Part.Image? {
        // "![alt]", then "(", the address (in <> or bare), an optional title in quotes, and ")"
        val altEnd = closing(text, 2, '[', ']') ?: return null
        if (text.getOrNull(altEnd + 1) != '(') return null
        var at = skipWhitespace(text, altEnd + 2)
        val addressEnd = if (text.getOrNull(at) == '<') text.indexOf('>', at) + 1 else bareAddressEnd(text, at)
        if (addressEnd <= at) return null
        val address = text.substring(at, addressEnd).removeSurrounding("<", ">")
        at = skipWhitespace(text, addressEnd)
        var title: String? = null
        val quote = text.getOrNull(at)
        if (quote == '"' || quote == '\'' || quote == '(') {
            val titleEnd = closing(text, at + 1, quote, if (quote == '(') ')' else quote) ?: return null
            title = text.substring(at + 1, titleEnd)
            at = skipWhitespace(text, titleEnd + 1)
        }
        if (text.getOrNull(at) != ')') return null
        val url = unescape(address).takeIf(ExternalLinks::isWebUrl) ?: return null
        return Part.Image(url, unescape(title ?: text.substring(2, altEnd)), text.substring(0, at + 1))
    }

    /** The index of the [close] that ends what was opened before [from], past any pairs inside it; null without one. */
    private fun closing(text: String, from: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i++
                c == close -> if (depth == 0) return i else depth--
                c == open -> depth++
            }
            i++
        }
        return null
    }

    /** Where an address that is not in <> ends: at a space, or at the bracket that closes the image. */
    private fun bareAddressEnd(text: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i++
                c.isWhitespace() -> return i
                c == '(' -> depth++
                c == ')' -> if (depth == 0) return i else depth--
            }
            i++
        }
        return text.length
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private val escape = Regex("""\\([!-/:-@\[-`{-~])""")

    /** Markdown's backslash before a punctuation mark, and "&amp;" for "&", as addresses and labels are written. */
    private fun unescape(text: String): String = text.replace(escape, "$1").replace("&amp;", "&")
}

/**
 * An entry's description with the images written in it, as Android's MangaSummary: while
 * Appearance > "Render images in manga descriptions" is on, an image shows in the text, at its
 * own size or the text's width when it is wider. Off, it is its label after an image icon, a link
 * to the image; an image that does not load shows the same way in the room the image had, so it
 * never takes the text with it.
 */
@Composable
internal fun DescriptionText(text: String, maxLines: Int, onTextLayout: (TextLayoutResult) -> Unit) {
    val preference = remember { Injekt.get<UiPreferences>().imagesInDescription() }
    val loadImages by preference.changes().collectAsState(preference.get())
    DescriptionImagesText(text, maxLines, onTextLayout, loadImages)
}

/**
 * [DescriptionText] with the preference read out, and the loader passed on (a test counts its
 * fetches). Every image holds its own slot in the text: the slots are laid out by position, and
 * a slot that left would rebuild the slots after it, fetching the images still loading in them
 * again. So a failed image keeps its slot and the slot shows its label instead ([FailedImage]
 * below); only the images on or off rebuilds the slots, and then there is nothing loading.
 */
@Composable
internal fun DescriptionImagesText(
    text: String,
    maxLines: Int,
    onTextLayout: (TextLayoutResult) -> Unit,
    loadImages: Boolean,
    loader: ImageLoader = Images.loader,
) {
    val style = MaterialTheme.typography.bodyMedium
    val parts = remember(text) { DescriptionImages.parse(text) }
    val images = remember(parts) { parts.filterIsInstance<Part.Image>() }
    if (images.isEmpty()) {
        Text(text, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, onTextLayout = onTextLayout)
        return
    }
    // Each loaded image's size in pixels by its address, or FAILED
    val sizes = remember(text) { mutableStateMapOf<String, IntSize>() }
    val failed = sizes.filterValues { it == FAILED }.keys
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(parts, loadImages, linkColor) { DescriptionImages.annotate(parts, loadImages, linkColor) }
    // The width the text has is the widest an image shows
    BoxWithConstraints {
        val iconSize = style.fontSize * 1.25
        val icon = Placeholder(iconSize, iconSize, PlaceholderVerticalAlign.TextCenter)
        val density = LocalDensity.current
        val iconPx = with(density) { iconSize.toPx() }
        val measurer = rememberTextMeasurer()
        // A failed image's label, as the link form shows it: bold, in the primary color
        val labelStyle = style.copy(fontWeight = FontWeight.Bold, color = linkColor)
        val inlineContent = buildMap {
            put(IMAGE_ICON, InlineTextContent(icon) { Icon(Icons.Outlined.Image, null, tint = linkColor) })
            images.forEach { image ->
                val failedNow = image.url in failed
                // An image takes an icon's room until it has loaded; a failed one, its label's
                val loaded = sizes[image.url]?.takeIf { it != FAILED }?.let { DescriptionImages.fit(it, constraints.maxWidth) }
                val placeholder = when {
                    loaded != null -> {
                        with(density) { Placeholder(loaded.width.toSp(), loaded.height.toSp(), PlaceholderVerticalAlign.Bottom) }
                    }
                    failedNow -> {
                        // As wide as the text is, wrapped and cut off as the text is
                        val measured = measurer.measure(
                            image.label,
                            style = labelStyle,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = maxLines,
                            constraints = Constraints(maxWidth = (constraints.maxWidth - iconPx).roundToInt()),
                        )
                        with(density) {
                            Placeholder(
                                (iconPx + measured.size.width).toSp(),
                                maxOf(iconPx, measured.size.height.toFloat()).toSp(),
                                PlaceholderVerticalAlign.Bottom,
                            )
                        }
                    }
                    else -> icon
                }
                put(
                    image.url,
                    InlineTextContent(placeholder) {
                        if (failedNow) {
                            FailedImage(image, labelStyle, linkColor, iconSize, maxLines)
                        } else {
                            AsyncImage(
                                // The whole image: its size is the room it takes in the text
                                model = remember(image.url) {
                                    ImageRequest.Builder(PlatformContext.INSTANCE).data(image.url).size(Size.ORIGINAL).build()
                                },
                                imageLoader = loader,
                                contentDescription = image.label.ifEmpty { null },
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart,
                                onSuccess = { state ->
                                    val loaded = IntSize(state.result.image.width, state.result.image.height)
                                    sizes[image.url] = loaded.takeIf { it.width > 0 && it.height > 0 } ?: FAILED
                                },
                                onError = { sizes[image.url] = FAILED },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
                )
            }
        }
        Text(
            annotated,
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = onTextLayout,
            inlineContent = inlineContent,
        )
    }
}

/**
 * A failed image, drawn in its own slot: its icon and label, as the link form shows them, and
 * clickable to the image as the link is.
 */
@Composable
private fun FailedImage(image: Part.Image, labelStyle: TextStyle, color: Color, iconSize: TextUnit, maxLines: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxSize()
            .clickable { openImage(image.url) }
            .testTag("failed-image"),
    ) {
        Icon(
            Icons.Outlined.Image,
            null,
            tint = color,
            modifier = Modifier.size(with(LocalDensity.current) { iconSize.toDp() }),
        )
        Text(image.label, style = labelStyle, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** Opens an image's address; a page that cannot open says so, as any link's page does. */
private fun openImage(url: String) {
    runCatching { ExternalLinks.browse(url) }.onFailure { Toasts.error(localized(DMR.strings.could_not_open_the_page), it) }
}

/** A link to an image, as Android's description links: bold, in the primary color. Only web addresses are images. */
private fun imageLink(url: String, color: Color) = LinkAnnotation.Clickable(
    tag = url,
    styles = TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.Bold)),
) { openImage(url) }

/** The inline content of the image icon before a label, as Android's MARKDOWN_INLINE_IMAGE_TAG. */
private const val IMAGE_ICON = "image-icon"

/** In place of a size, for an image that did not load. */
private val FAILED = IntSize.Zero
// ZOUKAN <--
