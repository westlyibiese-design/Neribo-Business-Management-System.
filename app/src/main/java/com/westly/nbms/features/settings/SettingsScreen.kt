package com.westly.nbms.features.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ErrorState
import com.westly.nbms.core.design.FormSection
import com.westly.nbms.core.design.LoadingState
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsDropdown
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.NbmsTimePickerField
import com.westly.nbms.core.design.PageHeader
import com.westly.nbms.core.session.SessionState
import kotlinx.datetime.LocalTime

/** Settings (Westly "Hotel Settings"): business code, hotel info, policies, housekeeping schedule, social links. */
@Composable
fun SettingsScreen(
    session: SessionState.SignedIn,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = hiltViewModel()
) {
    val load by vm.load.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(NbmsIcons.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            PageHeader(title = "Settings", subtitle = "Hotel configuration & contact details", modifier = Modifier.weight(1f))
        }

        val f = form
        when {
            f != null -> SettingsFormContent(
                code = session.business.code,
                f = f,
                errors = errors,
                saving = saving,
                onEdit = vm::edit,
                onCopy = {
                    copyText(context, session.business.code)
                    vm.codeCopied()
                },
                onSave = vm::save
            )
            load is Resource.Error -> ErrorState("We couldn't load settings.", onRetry = vm::retry)
            else -> LoadingState()
        }
    }
}

private fun copyText(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("Business code", text))
}

private fun hm(text: String): LocalTime? {
    val minutes = parseHm(text) ?: return null
    return LocalTime(minutes / 60, minutes % 60)
}

private fun hmText(t: LocalTime): String = formatHm(t.hour * 60 + t.minute)

@Composable
private fun SettingsFormContent(
    code: String,
    f: SettingsForm,
    errors: SettingsErrors,
    saving: Boolean,
    onEdit: ((SettingsForm) -> SettingsForm) -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().widthIn(max = 672.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Business code
        NbmsCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Business code", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 28.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    NbmsButton(text = "Copy", onClick = onCopy, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                }
                Text(
                    "Staff on shared devices enter this code with their PIN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 2. Hotel information
        SectionCard {
            FormSection("Hotel Information") {
                NbmsTextField(f.hotelName, { v -> onEdit { it.copy(hotelName = v) } }, "Hotel Name", Modifier.fillMaxWidth(), error = errors.hotelName)
                NbmsTextField(f.tagline, { v -> onEdit { it.copy(tagline = v) } }, "Tagline", Modifier.fillMaxWidth())
                NbmsTextField(f.phone, { v -> onEdit { it.copy(phone = v) } }, "Phone", Modifier.fillMaxWidth(), keyboardType = KeyboardType.Phone)
                NbmsTextField(f.email, { v -> onEdit { it.copy(email = v) } }, "Email", Modifier.fillMaxWidth(), keyboardType = KeyboardType.Email, error = errors.email)
                NbmsTextField(f.address, { v -> onEdit { it.copy(address = v) } }, "Address", Modifier.fillMaxWidth())
            }
        }

        // 3. Policies
        SectionCard {
            FormSection("Policies") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NbmsTimePickerField("Check-In Time", hm(f.checkInTime), { t -> onEdit { it.copy(checkInTime = hmText(t)) } }, Modifier.weight(1f))
                    NbmsTimePickerField("Check-Out Time", hm(f.checkOutTime), { t -> onEdit { it.copy(checkOutTime = hmText(t)) } }, Modifier.weight(1f))
                }
                NbmsTextField(
                    f.currency, { v -> onEdit { it.copy(currency = cleanCurrencyInput(v)) } }, "Currency",
                    Modifier.fillMaxWidth(), placeholder = "NGN", error = errors.currency
                )
            }
        }

        // 4. Housekeeping scheduling
        SectionCard {
            FormSection("Housekeeping Scheduling") {
                Text(
                    "Controls when the automatic cleaning queue adds rooms for housekeepers. Changes here take effect on the next scheduled run — no other configuration needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NbmsTextField(
                        f.leadMinutes, { v -> onEdit { it.copy(leadMinutes = cleanLeadInput(v)) } },
                        "Cleaning Lead Time (minutes before check-out)", Modifier.fillMaxWidth(),
                        keyboardType = KeyboardType.Number, error = errors.leadMinutes
                    )
                    if (errors.leadMinutes == null) {
                        Text(
                            leadTimeHelper(f.checkOutTime, f.leadMinutes.toIntOrNull() ?: 0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                NbmsTextField(
                    f.timezone, { v -> onEdit { it.copy(timezone = v) } }, "Hotel Timezone (IANA)",
                    Modifier.fillMaxWidth(), placeholder = "Africa/Lagos", error = errors.timezone
                )
                NbmsTimePickerField(
                    "Daily Service Time (occupied / extended-stay rooms)", hm(f.serviceTime),
                    { t -> onEdit { it.copy(serviceTime = hmText(t)) } }, Modifier.fillMaxWidth()
                )
                NbmsDropdown(
                    label = "Occupied-Room Daily Housekeeping",
                    options = listOf(true, false),
                    selected = f.serviceEnabled,
                    onSelect = { v -> onEdit { it.copy(serviceEnabled = v) } },
                    optionLabel = { if (it) "Enabled" else "Disabled" },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 5. Social media
        SectionCard {
            FormSection("Social Media") {
                NbmsTextField(f.instagram, { v -> onEdit { it.copy(instagram = v) } }, "Instagram URL", Modifier.fillMaxWidth(), placeholder = "https://…", keyboardType = KeyboardType.Uri)
                NbmsTextField(f.facebook, { v -> onEdit { it.copy(facebook = v) } }, "Facebook URL", Modifier.fillMaxWidth(), placeholder = "https://…", keyboardType = KeyboardType.Uri)
                NbmsTextField(f.twitter, { v -> onEdit { it.copy(twitter = v) } }, "Twitter / X URL", Modifier.fillMaxWidth(), placeholder = "https://…", keyboardType = KeyboardType.Uri)
            }
        }

        NbmsButton(
            text = if (saving) "Saving…" else "Save Settings",
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            loading = saving,
            leadingIcon = if (saving) null else NbmsIcons.Check
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}
