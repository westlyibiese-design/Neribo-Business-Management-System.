package com.westly.nbms.core.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KingBed
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveModerator
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Westly (lucide) icon names mapped to Material Icons (outlined style), Appendix B section 11.
 * Use these names everywhere so every screen picks the same icon for the same idea.
 */
object NbmsIcons {
    val Dashboard: ImageVector get() = Icons.Outlined.Dashboard
    val Bed: ImageVector get() = Icons.Outlined.KingBed
    val CalendarCheck: ImageVector get() = Icons.Outlined.EventAvailable
    val CalendarClock: ImageVector get() = Icons.Outlined.EventRepeat
    val Calendar: ImageVector get() = Icons.Outlined.CalendarMonth
    val Users: ImageVector get() = Icons.Outlined.Group
    val UserCog: ImageVector get() = Icons.Outlined.ManageAccounts
    val User: ImageVector get() = Icons.Outlined.Person
    val UserCheck: ImageVector get() = Icons.Outlined.HowToReg
    val UserX: ImageVector get() = Icons.Outlined.PersonOff
    val Shield: ImageVector get() = Icons.Outlined.Shield
    val ShieldOff: ImageVector get() = Icons.Outlined.RemoveModerator
    val ShieldCheck: ImageVector get() = Icons.Outlined.VerifiedUser
    val FileBarChart: ImageVector get() = Icons.Outlined.Assessment
    val BarChart: ImageVector get() = Icons.Outlined.BarChart
    val History: ImageVector get() = Icons.Outlined.History
    val Settings: ImageVector get() = Icons.Outlined.Settings
    val Tune: ImageVector get() = Icons.Outlined.Tune
    val Package: ImageVector get() = Icons.Outlined.Inventory2
    val PackageSearch: ImageVector get() = Icons.Outlined.ManageSearch
    val Sparkles: ImageVector get() = Icons.Outlined.AutoAwesome
    val ClipboardCheck: ImageVector get() = Icons.Outlined.Checklist
    val LogOut: ImageVector get() = Icons.AutoMirrored.Outlined.Logout
    val Menu: ImageVector get() = Icons.Outlined.Menu
    val Close: ImageVector get() = Icons.Outlined.Close
    val ChevronDown: ImageVector get() = Icons.Outlined.ExpandMore
    val ChevronUp: ImageVector get() = Icons.Outlined.ExpandLess
    val ChevronRight: ImageVector get() = Icons.Outlined.ChevronRight
    val ArrowLeft: ImageVector get() = Icons.AutoMirrored.Outlined.ArrowBack
    val ArrowUpRight: ImageVector get() = Icons.Outlined.NorthEast
    val Banknote: ImageVector get() = Icons.Outlined.Payments
    val TrendingUp: ImageVector get() = Icons.Outlined.TrendingUp
    val Receipt: ImageVector get() = Icons.Outlined.Receipt
    val Coffee: ImageVector get() = Icons.Outlined.Coffee
    val Wrench: ImageVector get() = Icons.Outlined.Build
    val Construction: ImageVector get() = Icons.Outlined.Construction
    val Bell: ImageVector get() = Icons.Outlined.Notifications
    val ShoppingCart: ImageVector get() = Icons.Outlined.ShoppingCart
    val BookOpen: ImageVector get() = Icons.Outlined.MenuBook
    val Archive: ImageVector get() = Icons.Outlined.Archive
    val Building: ImageVector get() = Icons.Outlined.Business
    val Utensils: ImageVector get() = Icons.Outlined.Restaurant
    val Globe: ImageVector get() = Icons.Outlined.Language
    val Images: ImageVector get() = Icons.Outlined.Collections
    val Reviews: ImageVector get() = Icons.Outlined.RateReview
    val Download: ImageVector get() = Icons.Outlined.Download
    val Mail: ImageVector get() = Icons.Outlined.Mail
    val Wine: ImageVector get() = Icons.Outlined.WineBar
    val Laundry: ImageVector get() = Icons.Outlined.LocalLaundryService
    val Landmark: ImageVector get() = Icons.Outlined.AccountBalance
    val Dumbbell: ImageVector get() = Icons.Outlined.FitnessCenter
    val Activity: ImageVector get() = Icons.Outlined.MonitorHeart
    val Key: ImageVector get() = Icons.Outlined.Key
    val Lock: ImageVector get() = Icons.Outlined.Lock
    val Tag: ImageVector get() = Icons.Outlined.Sell
    val Search: ImageVector get() = Icons.Outlined.Search
    val Plus: ImageVector get() = Icons.Outlined.Add
    val Eye: ImageVector get() = Icons.Outlined.Visibility
    val EyeOff: ImageVector get() = Icons.Outlined.VisibilityOff
    val CheckCircle: ImageVector get() = Icons.Outlined.CheckCircle
    val XCircle: ImageVector get() = Icons.Outlined.Cancel
    val Check: ImageVector get() = Icons.Outlined.Check
    val Clock: ImageVector get() = Icons.Outlined.Schedule
    val Phone: ImageVector get() = Icons.Outlined.Phone
    val Refresh: ImageVector get() = Icons.Outlined.Refresh
    val Pencil: ImageVector get() = Icons.Outlined.Edit
    val Trash: ImageVector get() = Icons.Outlined.Delete
    val AlertTriangle: ImageVector get() = Icons.Outlined.Warning
    val Backspace: ImageVector get() = Icons.AutoMirrored.Outlined.Backspace
}
