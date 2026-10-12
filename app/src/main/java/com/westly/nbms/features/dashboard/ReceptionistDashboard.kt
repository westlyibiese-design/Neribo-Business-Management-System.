package com.westly.nbms.features.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsPill
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.session.SessionManager
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.features.dashboard.data.DashBooking
import com.westly.nbms.features.dashboard.data.DashboardRepository
import com.westly.nbms.features.messages.InboxMessage
import com.westly.nbms.features.messages.MESSAGE_ROLES
import com.westly.nbms.features.messages.MessagesApi
import com.westly.nbms.features.messages.firstLine
import com.westly.nbms.features.messages.unreadOf
import com.westly.nbms.shell.firstName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/** How many website messages the Recent Messages card shows. */
internal const val RECEPTION_MESSAGE_COUNT = 4

/** The live lists the Receptionist dashboard reads (bookings only; messages come from Phase 15's inbox). */
internal class ReceptionFlows(val bookings: Flow<Resource<List<DashBooking>>>)

/** Everything the Receptionist screen shows apart from the messages card. */
internal data class ReceptionState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val now: Instant = Instant.EPOCH,
    val expectedIn: Int = 0,
    val expectedOut: Int = 0,
    val inHouse: Int = 0,
    val arrivals: List<DashBooking> = emptyList(),
    val departures: List<DashBooking> = emptyList()
)

/** The Recent Messages card. [visible] is false until the inbox has loaded, after an error, and for a role without access. */
internal data class ReceptionMessagesState(
    val visible: Boolean = false,
    val unread: Int = 0,
    val recent: List<InboxMessage> = emptyList()
)

/** Works out the Receptionist numbers and lists from the live bookings. Pure: all numbers come from [FrontlineMath]. */
internal fun receptionStateOf(bookings: Resource<List<DashBooking>>, now: Instant, zone: ZoneId): ReceptionState {
    val failed = anyFailed(bookings)
    val loading = !failed && anyLoading(bookings)
    val stats = FrontlineMath.receptionStats(bookings.rowsOrEmpty(), now, zone)
    return ReceptionState(
        loading = loading,
        failed = failed,
        now = now,
        expectedIn = stats.expectedIn.size,
        expectedOut = stats.expectedOut.size,
        inHouse = stats.inHouse,
        arrivals = stats.expectedIn.take(RECEPTION_LIST_COUNT),
        departures = stats.expectedOut.take(RECEPTION_LIST_COUNT)
    )
}

/** The newest [RECEPTION_MESSAGE_COUNT] messages and the number of unread ones; hidden unless the inbox [loaded] and the role is [allowed]. */
internal fun receptionMessagesOf(list: List<InboxMessage>, loaded: Boolean, allowed: Boolean): ReceptionMessagesState =
    if (!allowed || !loaded) ReceptionMessagesState()
    else ReceptionMessagesState(
        visible = true,
        unread = unreadOf(list),
        recent = list.sortedByDescending { it.createdAt }.take(RECEPTION_MESSAGE_COUNT)
    )

/** Receptionist dashboard data. All numbers are live and computed in the business time zone. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ReceptionistDashboardViewModel internal constructor(
    private val flows: () -> ReceptionFlows,
    private val zone: ZoneId,
    private val navigator: ShellNavigator,
    private val clock: Flow<Instant>,
    messages: Flow<List<InboxMessage>>,
    private val messagesAllowed: Boolean,
    private val startMessageTracking: () -> Unit,
    private val loadMessages: suspend () -> Boolean
) : ViewModel() {

    @Inject
    constructor(
        repository: DashboardRepository,
        session: SessionManager,
        navigator: ShellNavigator,
        messagesApi: MessagesApi
    ) : this(
        flows = { ReceptionFlows(repository.bookings()) },
        zone = zoneOfSession(session),
        navigator = navigator,
        clock = dashClock(),
        messages = messagesApi.messages,
        messagesAllowed = messagesAllowedFor(session),
        startMessageTracking = messagesApi::ensureTracking,
        loadMessages = { messagesApi.list().isSuccess }
    )

    private val attempt = MutableStateFlow(0)
    private val messagesLoaded = MutableStateFlow(false)

    internal val state: StateFlow<ReceptionState> = attempt
        .flatMapLatest {
            flows().bookings.combine(clock) { bookings, now -> receptionStateOf(bookings, now, zone) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceptionState())

    internal val messageState: StateFlow<ReceptionMessagesState> =
        combine(messages, messagesLoaded) { list, loaded -> receptionMessagesOf(list, loaded, messagesAllowed) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReceptionMessagesState())

    init {
        if (messagesAllowed) {
            startMessageTracking()
            loadInbox()
        }
    }

    private fun loadInbox() {
        viewModelScope.launch { messagesLoaded.value = loadMessages() }
    }

    /** Retry after an error: collects the bookings again (and the inbox, if it has not loaded yet). */
    fun retry() {
        attempt.value += 1
        if (messagesAllowed && !messagesLoaded.value) loadInbox()
    }

    /** Opens a page by route (every tile, row button and the messages card go through here). */
    fun open(route: String) = navigator.open(route)
}

/** True when the signed-in role may read the message inbox (Receptionist, Manager, Super Admin). */
internal fun messagesAllowedFor(session: SessionManager): Boolean {
    val role = (session.state.value as? SessionState.SignedIn)?.user?.role
    return role != null && role in MESSAGE_ROLES
}

/** The Dashboard of the Receptionist. */
@Composable
fun ReceptionistDashboard(session: SessionState.SignedIn) {
    val viewModel: ReceptionistDashboardViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val messages by viewModel.messageState.collectAsState()
    val zone = rememberBusinessZone(session)
    val nbms = MaterialTheme.nbms
    val orange = orangeTone()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashGreeting(
            title = "Welcome, ${firstName(session.user.name)}",
            subtitle = longDateText(nowOrReal(state.now), zone)
        )
        FrontlineStateHost(state.loading, state.failed, MSG_RECEPTION_LOAD_FAILED, viewModel::retry) {
            WithWidth { width ->
                TileGrid(
                    columnsFor(width, narrow = 2, wide = 5),
                    listOf(
                        { ColourTile("Check-In", Icons.Outlined.PersonAdd, Color(0xFF16A34A)) { viewModel.open(ROUTE_CHECKIN) } },
                        { ColourTile("Check Out", NbmsIcons.LogOut, Color(0xFFEA580C)) { viewModel.open(ROUTE_CHECKOUT) } },
                        { ColourTile("Room Reservations", NbmsIcons.Globe, Color(0xFF2563EB)) { viewModel.open(ROUTE_ROOM_RESERVATIONS) } },
                        { ColourTile("Messages", NbmsIcons.Mail, Color(0xFF7E22CE)) { viewModel.open(ROUTE_MESSAGES) } },
                        { ColourTile("All Bookings", NbmsIcons.CalendarCheck, null) { viewModel.open(ROUTE_BOOKINGS) } }
                    )
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FrontlineStatCard("Expected In", state.expectedIn.toString(), nbms.success, Modifier.weight(1f))
                FrontlineStatCard("Expected Out", state.expectedOut.toString(), orange, Modifier.weight(1f))
                FrontlineStatCard("In-House", state.inHouse.toString(), nbms.info, Modifier.weight(1f))
            }
            WithWidth { width ->
                val arrivals: @Composable (Modifier) -> Unit = { m ->
                    FrontlineSectionCard("Today's Arrivals", NbmsIcons.UserCheck, nbms.success, m) {
                        if (state.arrivals.isEmpty()) {
                            FrontlineEmptyText("No arrivals today")
                        } else {
                            state.arrivals.forEach { b ->
                                RowItem(
                                    primary = guestLabel(b.guestName),
                                    secondary = roomLabel(b.roomNumber),
                                    trailing = {
                                        NbmsButton("Check In", { viewModel.open(ROUTE_ROOM_RESERVATIONS) }, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                                    }
                                )
                            }
                        }
                    }
                }
                val departures: @Composable (Modifier) -> Unit = { m ->
                    FrontlineSectionCard("Today's Departures", NbmsIcons.LogOut, orange, m) {
                        if (state.departures.isEmpty()) {
                            FrontlineEmptyText("No departures today")
                        } else {
                            state.departures.forEach { b ->
                                RowItem(
                                    primary = guestLabel(b.guestName),
                                    secondary = roomLabel(b.roomNumber),
                                    trailing = {
                                        NbmsButton("Check Out", { viewModel.open(ROUTE_CHECKOUT) }, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                                    }
                                )
                            }
                        }
                    }
                }
                if (width >= 600.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        arrivals(Modifier.weight(1f))
                        departures(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        arrivals(Modifier)
                        departures(Modifier)
                    }
                }
            }
            if (messages.visible) {
                RecentMessagesCard(messages, nowOrReal(state.now), viewModel)
            }
        }
    }
}

@Composable
private fun RecentMessagesCard(state: ReceptionMessagesState, now: Instant, viewModel: ReceptionistDashboardViewModel) {
    val scheme = MaterialTheme.colorScheme
    val purple = purpleTone()
    FrontlineSectionCard(
        title = "Recent Messages",
        icon = NbmsIcons.Mail,
        iconTint = purple,
        headerTrailing = {
            if (state.unread > 0) {
                NbmsPill("${state.unread} new", MaterialTheme.nbms.rolePill("super_admin"))
            }
            NbmsButton("View all", { viewModel.open(ROUTE_MESSAGES) }, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
        }
    ) {
        if (state.recent.isEmpty()) {
            FrontlineEmptyText("No messages yet")
        } else {
            state.recent.forEach { m ->
                Column(Modifier.fillMaxWidth().clickable { viewModel.open(ROUTE_MESSAGES) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.name.ifBlank { "Guest" },
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (m.isNew) FontWeight.SemiBold else FontWeight.Normal),
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                m.subject?.takeIf { it.isNotBlank() } ?: firstLine(m.message),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            Format.relative(m.createdAt, now = kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilli())),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.nbms.cardBorder)
                }
            }
        }
    }
}

/** A quick-action tile filled with [fill] (white label), or with the muted surface colour when [fill] is null. */
@Composable
private fun ColourTile(label: String, icon: ImageVector, fill: Color?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container = fill ?: scheme.surfaceVariant
    val content = if (fill != null) Color.White else scheme.onSurface
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
            Text(
                label,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = content,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
