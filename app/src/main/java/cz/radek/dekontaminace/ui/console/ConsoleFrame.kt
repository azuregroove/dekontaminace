package cz.radek.dekontaminace.ui.console

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import cz.radek.dekontaminace.ui.theme.Console
import cz.radek.dekontaminace.ui.theme.Display
import kotlin.random.Random
import kotlinx.coroutines.delay

enum class Section(val code: String, val label: String, val color: Color, val phase: Int) {
    OVERVIEW("01", "MŮSTEK", Console.Sky, 1),
    CACHE("02", "FILTRY", Console.Teal, 1),
    FILES("03", "SKLAD", Console.Azure, 2),
    CARGO("04", "NÁKLAD", Console.Lime, 2),
    RULES("05", "ROZKAZY", Console.Violet, 3),
    HISTORY("06", "DENÍK", Console.Amber, 4),
}

/** Moduly z fází do této včetně jsou hotové a v bočnici svítí plnou barvou. */
const val ACTIVE_PHASE = 4

private val SideWidth = 112.dp
private val BarHeight = 26.dp
private val OuterRadius = 44.dp
private val InnerRadius = 18.dp

/**
 * Písmo konzole v pevné velikosti: bočnice má pevnou šířku, takže zvětšené systémové písmo
 * by popisky usekávalo. Obsah modulů se systémovému nastavení dál přizpůsobuje.
 */
@Composable
private fun fixed(size: Float): TextUnit = with(LocalDensity.current) { (size / fontScale).sp }

/** Ovládací prvky (bočnice, údaje lodi) mají stejné písmo jako nadpisy, v pevné velikosti. */
@Composable
private fun consoleText(size: Float): TextStyle = MaterialTheme.typography.labelMedium.copy(
    fontFamily = Display,
    fontWeight = FontWeight.Normal,
    fontSize = fixed(size),
    letterSpacing = fixed(0.4f),
)

@Composable
fun ConsoleFrame(
    title: String,
    current: Section,
    alert: Boolean,
    onSelect: (Section) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Console.Black)
            .systemBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        TopElbow(title = title, color = if (alert) Console.Rose else Console.Periwinkle)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(SideWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Section.entries.forEachIndexed { i, s ->
                    SideButton(
                        section = s,
                        selected = s == current,
                        index = i,
                        onClick = { onSelect(s) },
                    )
                }
                SideFiller(alert = alert, modifier = Modifier.weight(1f))
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 12.dp, top = 4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun TopElbow(title: String, color: Color) {
    // Při startu nadpis párkrát zabliká, jako když naskakuje konzole.
    val flicker = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        flicker.animateTo(
            1f,
            keyframes {
                durationMillis = 900
                0f at 0
                1f at 120
                0.2f at 220
                1f at 340
                0.55f at 460
                1f at 640
            },
        )
    }
    Box(Modifier.fillMaxWidth().height(64.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = SideWidth.toPx()
            val bar = BarHeight.toPx()
            val outer = OuterRadius.toPx()
            val inner = InnerRadius.toPx()
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f, top = 0f, right = w, bottom = size.height,
                        topLeftCornerRadius = CornerRadius(outer, outer),
                    ),
                )
            }
            drawPath(path, color)
            drawRect(color, topLeft = Offset(w - 1f, 0f), size = Size(size.width - w + 1f, bar))
            // Vnitřní zaoblení lokte: barevný čtverec a přes něj černý kruh.
            drawRect(color, topLeft = Offset(w, bar), size = Size(inner, inner))
            drawCircle(Console.Black, radius = inner, center = Offset(w + inner, bar + inner))
        }
        Text(
            text = "DK-0920",
            style = consoleText(10f),
            color = Console.Black,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 6.dp),
        )
        Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
            Box(
                // Bez pevné výšky: písmo smí přesahovat pruh dolů do černé, jinak se ořízne.
                Modifier.background(Console.Black).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alpha(flicker.value),
                )
            }
            Box(
                Modifier
                    .width(28.dp)
                    .height(BarHeight)
                    .background(color, RoundedCornerShape(topEnd = 13.dp, bottomEnd = 13.dp)),
            )
        }
    }
}

private val SegmentShape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp, topEnd = 6.dp, bottomEnd = 6.dp)

/**
 * Moderní segment bočnice: nevybraný je průhledný s barevnou hranou směrem k obsahu,
 * vybraný se plně rozsvítí a kolem něj pomalu „dýchá“ záře. Panel žije i v klidu:
 * segmenty postupně přejíždí odlesk a kontrolky aktivity blikají v náhodném rytmu.
 */
@Composable
private fun SideButton(section: Section, selected: Boolean, index: Int, onClick: () -> Unit) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(80L * index)
        enter.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    val ready = section.phase <= ACTIVE_PHASE
    val base = if (ready) section.color else section.color.copy(alpha = 0.45f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "press")
    val fill by animateColorAsState(
        when {
            selected -> base
            pressed -> base.copy(alpha = 0.38f)
            else -> base.copy(alpha = 0.14f)
        },
        tween(300),
        label = "fill",
    )
    val ink by animateColorAsState(if (selected) Console.Black else base, tween(300), label = "ink")
    val edge by animateDpAsState(if (selected) 0.dp else 4.dp, tween(300), label = "edge")
    val transition = rememberInfiniteTransition(label = "segment")
    val glow by transition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.32f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "glowAlpha",
    )
    // Posun podle pořadí: odlesk sjíždí panelem odshora dolů jako vlna, ne všude naráz.
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(5200, easing = LinearEasing),
            RepeatMode.Restart,
            initialStartOffset = StartOffset(index * 260),
        ),
        label = "sweep",
    )
    val flash = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected) {
            flash.snapTo(0.65f)
            flash.animateTo(0f, tween(480))
        }
    }
    var led by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(Random.nextLong(300, 1500))
        while (true) {
            led = true
            delay(Random.nextLong(70, 240))
            led = false
            delay(Random.nextLong(500, 3200))
        }
    }
    Box(
        Modifier
            .graphicsLayer {
                translationX = (1f - enter.value) * -60.dp.toPx()
                alpha = enter.value
                scaleX = scale
                scaleY = scale
            }
            .fillMaxWidth()
            .height(56.dp)
            .drawBehind {
                if (selected) {
                    // Záře se kreslí kolem segmentu, proto před oříznutím tvarem.
                    val pad = 4.dp.toPx()
                    drawRoundRect(
                        color = base.copy(alpha = glow),
                        topLeft = Offset(-pad, -pad),
                        size = Size(size.width + pad * 2, size.height + pad * 2),
                        cornerRadius = CornerRadius(26.dp.toPx(), 26.dp.toPx()),
                    )
                }
            }
            .clip(SegmentShape)
            .background(fill)
            .drawWithContent {
                drawContent()
                val band = size.width * 0.45f
                val x = (sweep * 4f - 1f) * size.width
                if (x > -band && x < size.width) {
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = if (selected) 0.30f else 0.16f), Color.Transparent),
                            startX = x,
                            endX = x + band,
                        ),
                    )
                }
                if (flash.value > 0f) drawRect(Color.White.copy(alpha = flash.value))
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(edge)
                .fillMaxHeight()
                .background(base),
        )
        Text(
            text = section.code,
            style = consoleText(11f),
            color = ink.copy(alpha = if (selected) 0.75f else 0.9f),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 6.dp),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 9.dp, end = 12.dp)
                .size(5.dp)
                .background(
                    when {
                        led -> if (selected) Console.Black else base
                        else -> (if (selected) Console.Black else base).copy(alpha = 0.22f)
                    },
                    CircleShape,
                ),
        )
        Text(
            text = section.label,
            style = consoleText(15f),
            color = ink,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp),
        )
    }
}

/**
 * Smyšlené palubní systémy, jen pro atmosféru: hodnoty jemně kolísají a při výstraze se zhorší.
 * Pořadí je zároveň priorita: co se do bočnice nevejde, vypadne odspodu.
 */
@Composable
private fun rememberShipStatus(alert: Boolean): List<Pair<String, List<Pair<String, String>>>> {
    var shields by remember { mutableIntStateOf(100) }
    var dilithium by remember { mutableIntStateOf(87) }
    var warp by remember { mutableIntStateOf(2140) }
    var sensor by remember { mutableIntStateOf(0) }
    var speed by remember { mutableIntStateOf(62) }
    var hull by remember { mutableIntStateOf(100) }
    var heading by remember { mutableIntStateOf(142) }
    var mark by remember { mutableIntStateOf(7) }
    var transporter by remember { mutableIntStateOf(0) }
    var crew by remember { mutableIntStateOf(0) }
    var antimatter by remember { mutableIntStateOf(74) }
    var gravity by remember { mutableIntStateOf(100) }
    var channel by remember { mutableIntStateOf(3) }
    var replicator by remember { mutableIntStateOf(0) }
    var holodeck by remember { mutableIntStateOf(0) }
    var temperature by remember { mutableIntStateOf(215) }
    var clock by remember { mutableStateOf(shipClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_500)
            shields = (shields + Random.nextInt(-2, 3)).coerceIn(94, 100)
            dilithium = (dilithium + Random.nextInt(-1, 2)).coerceIn(80, 95)
            warp = (warp + Random.nextInt(-15, 16)).coerceIn(2080, 2200)
            sensor = (sensor + 1) % 4
            speed = (speed + Random.nextInt(-1, 2)).coerceIn(55, 70)
            hull = (hull + Random.nextInt(-1, 2)).coerceIn(97, 100)
            heading = (heading + Random.nextInt(-1, 2) + 360) % 360
            if (Random.nextInt(5) == 0) mark = (mark + Random.nextInt(-1, 2)).coerceIn(0, 9)
            if (Random.nextInt(4) == 0) transporter = (transporter + 1) % 3
            if (Random.nextInt(6) == 0) crew = (crew + 1) % 3
            antimatter = (antimatter + Random.nextInt(-1, 2)).coerceIn(68, 80)
            gravity = (gravity + Random.nextInt(-1, 2)).coerceIn(98, 102)
            if (Random.nextInt(8) == 0) channel = Random.nextInt(1, 13)
            if (Random.nextInt(7) == 0) replicator = (replicator + 1) % 4
            if (Random.nextInt(9) == 0) holodeck = (holodeck + 1) % 2
            temperature = (temperature + Random.nextInt(-1, 2)).coerceIn(208, 222)
            clock = shipClock()
        }
    }
    val sweep = listOf("SKENUJÍ", "SEKTOR 1", "SEKTOR 2", "SEKTOR 3")[sensor]
    // Skupiny podle palubních oddělení; pořadí skupin je zároveň priorita, co se nevejde, vypadne celé.
    return listOf(
        "ČAS" to listOf(
            "HV. DATUM" to formatStardate(stardate()),
            "LODNÍ ČAS" to clock,
        ),
        "OBRANA" to listOf(
            "ŠTÍTY" to if (alert) "OFFLINE" else "$shields %",
            "DEFLEKTOR" to if (alert) "VÝPADEK" else "AKTIVNÍ",
            "TRUP" to "$hull %",
        ),
        "POHON" to listOf(
            "WARP JÁDRO" to "$warp K",
            "ANTIHMOTA" to "$antimatter %",
            "DILITHIUM" to "$dilithium %",
            "RYCHLOST" to if (alert) "IMPULZ" else "WARP ${speed / 10},${speed % 10}",
            "KURZ" to "${heading.toString().padStart(3, '0')} MARK $mark",
        ),
        "SYSTÉMY" to listOf(
            "SENZORY" to sweep,
            "KOMUNIKACE" to if (alert) "RUŠENÍ" else "KANÁL $channel",
            "TRANSPORTÉR" to if (alert) "ZAMČENO" else listOf("PŘIPRAVEN", "NABÍJÍ", "PŘIPRAVEN")[transporter],
        ),
        "PROSTŘEDÍ" to listOf(
            "ATMOSFÉRA" to "OK",
            "GRAVITACE" to "${gravity / 100},${(gravity % 100).toString().padStart(2, '0')} G",
            "TEPLOTA" to "${temperature / 10},${temperature % 10} °C",
        ),
        "POSÁDKA" to listOf(
            "POSÁDKA" to listOf("VE SLUŽBĚ", "VOLNO", "VE SLUŽBĚ")[crew],
            "REPLIKÁTOR" to listOf("PŘIPRAVEN", "EARL GREY", "PŘIPRAVEN", "KÁVA")[replicator],
            "HOLOPALUBA" to if (holodeck == 0) "VOLNÁ" else "OBSAZENO",
        ),
    )
}

private fun shipClock(): String =
    java.time.LocalTime.now().let { "${it.hour}:${it.minute.toString().padStart(2, '0')}" }

/** Skládá položky pod sebe; ty, které se už nevejdou, vynechá celé, aby se neusekly v půlce. */
@Composable
private fun FitColumn(spacing: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placed = ArrayList<Placeable>()
        var used = 0
        for (m in measurables) {
            val p = m.measure(loose)
            val next = used + (if (placed.isEmpty()) 0 else gap) + p.height
            if (constraints.hasBoundedHeight && next > constraints.maxHeight) break
            placed += p
            used = next
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else (placed.maxOfOrNull { it.width } ?: 0)
        layout(width, used) {
            var y = 0
            placed.forEach { p ->
                p.placeRelative(width - p.width, y)
                y += p.height + gap
            }
        }
    }
}

@Composable
private fun SideFiller(alert: Boolean, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "filler")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "alertAlpha",
    )
    val scan by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "scan",
    )
    val accent = if (alert) Console.Rose else Console.Violet
    val ship = rememberShipStatus(alert)
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = OuterRadius))
            .background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = if (alert) 0.35f * blink else 0.28f), Console.Azure.copy(alpha = 0.06f)),
                ),
            )
            .drawWithContent {
                drawContent()
                // Stupnice na pravé hraně a pomalu jedoucí skenovací linka.
                val tick = 14.dp.toPx()
                var y = tick
                while (y < size.height) {
                    drawRect(
                        accent.copy(alpha = 0.45f),
                        topLeft = Offset(size.width - 8.dp.toPx(), y),
                        size = Size(5.dp.toPx(), 1.dp.toPx()),
                    )
                    y += tick
                }
                drawRect(
                    Console.Sky.copy(alpha = 0.35f),
                    topLeft = Offset(0f, scan * size.height),
                    size = Size(size.width, 2.dp.toPx()),
                )
            }
            .padding(start = 8.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        FitColumn(spacing = 10.dp) {
            if (alert) {
                Text(
                    "VÝSTRAHA",
                    style = consoleText(12f),
                    color = Console.Rose.copy(alpha = blink),
                )
            }
            ship.forEach { (group, readouts) ->
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    // Hlavička oddělení: název a linka přes celou šířku skupiny.
                    Text(group, style = consoleText(8f), color = accent.copy(alpha = 0.95f), maxLines = 1)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.45f)))
                    readouts.forEach { (label, value) ->
                        Readout(label, value, if (alert && value in setOf("OFFLINE", "VÝPADEK", "IMPULZ", "ZAMČENO", "RUŠENÍ")) Console.Rose else Console.Lilac)
                    }
                }
            }
        }
    }
}

@Composable
private fun Readout(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            label,
            style = consoleText(9f),
            color = color.copy(alpha = 0.6f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        // Při změně hodnota vyjede zdola a stará odjede nahoru.
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically(tween(350)) { it } + fadeIn(tween(350))) togetherWith
                    (slideOutVertically(tween(250)) { -it } + fadeOut(tween(250)))
            },
            label = "readout-$label",
        ) { v ->
            Text(
                v,
                style = consoleText(11f),
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Kulatá „pilulka“, hlavní ovládací prvek konzole. */
@Composable
fun Pill(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .background(if (enabled) color else Console.Gray, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        // Písmo jako nadpisy; je široké, takže dlouhý popisek se raději zmenší, než aby se usekl.
        BasicText(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(
                fontFamily = Display,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.5.sp,
                color = if (enabled) Console.Black else Console.Dim,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 15.sp, stepSize = 0.5.sp),
        )
    }
}

/** Nadpis sekce: zaoblený začátek, text, pruh do konce řádku. */
@Composable
fun SectionHeader(text: String, color: Color, modifier: Modifier = Modifier) {
    // Výšku řádku určuje text, pruhy se mu přizpůsobí, takže se nic neusekne ani při větším písmu.
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(16.dp)
                .fillMaxHeight()
                .padding(vertical = 2.dp)
                .background(color, RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)),
        )
        // Pruh leží pod textem: dlouhý nadpis ve širokém písmu se pak zkrátí na „…“, místo aby se usekl.
        Box(Modifier.weight(1f)) {
            Box(Modifier.matchParentSize().padding(vertical = 2.dp).background(color))
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = color,
                modifier = Modifier.background(Console.Black).padding(horizontal = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

data class MeterPart(val fraction: Float, val color: Color)

/** Vodorovný dělený ukazatel, např. obsazenost úložiště nebo průběh. */
@Composable
fun Meter(parts: List<MeterPart>, modifier: Modifier = Modifier, height: Dp = 18.dp, background: Color = Console.Gray) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    Row(
        modifier.fillMaxWidth().height(height).background(background),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Weight musí být kladná, proto nulové a zatím nevyrostlé části vynecháváme.
        val shown = parts.map { it to it.fraction * reveal.value }.filter { it.second > 0.002f }
        val rest = (1f - shown.sumOf { it.second.toDouble() }.toFloat()).coerceAtLeast(0f)
        shown.forEach { (p, w) ->
            Box(Modifier.weight(w).fillMaxHeight().background(p.color))
        }
        if (rest > 0.002f) Spacer(Modifier.weight(rest))
    }
}
