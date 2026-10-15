package dev.duardo.neotransfer.plus.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.skeleton
import dev.duardo.neotransfer.plus.preview.PreviewData.bandec
import dev.duardo.neotransfer.plus.preview.PreviewData.banmet
import dev.duardo.neotransfer.plus.preview.PreviewData.bpa
import dev.duardo.neotransfer.plus.preview.PreviewData.cup
import dev.duardo.neotransfer.plus.preview.PreviewData.wallet
import dev.duardo.neotransfer.plus.ui.activity.ActivityScreen
import dev.duardo.neotransfer.plus.ui.activity.OperationDetailScreen
import dev.duardo.neotransfer.plus.ui.contacts.ContactDetailScreen
import dev.duardo.neotransfer.plus.ui.contacts.ContactEditScreen
import dev.duardo.neotransfer.plus.ui.contacts.ContactsScreen
import dev.duardo.neotransfer.plus.ui.home.HomeScreen
import dev.duardo.neotransfer.plus.ui.lock.LockScreen
import dev.duardo.neotransfer.plus.ui.login.LoginScreen
import dev.duardo.neotransfer.plus.ui.model.ActivityContent
import dev.duardo.neotransfer.plus.ui.model.ActivityFilter
import dev.duardo.neotransfer.plus.ui.model.ActivitySource
import dev.duardo.neotransfer.plus.ui.model.ActivityUi
import dev.duardo.neotransfer.plus.ui.model.BalanceUi
import dev.duardo.neotransfer.plus.ui.model.BillUi
import dev.duardo.neotransfer.plus.ui.model.CardSheetUi
import dev.duardo.neotransfer.plus.ui.model.ContactEditUi
import dev.duardo.neotransfer.plus.ui.model.ContactsContent
import dev.duardo.neotransfer.plus.ui.model.ContactsUi
import dev.duardo.neotransfer.plus.ui.model.EditableCardUi
import dev.duardo.neotransfer.plus.ui.model.EditablePhoneUi
import dev.duardo.neotransfer.plus.ui.model.ForeignCardUi
import dev.duardo.neotransfer.plus.ui.model.HomeUi
import dev.duardo.neotransfer.plus.ui.model.LockUi
import dev.duardo.neotransfer.plus.ui.model.LoginProblem
import dev.duardo.neotransfer.plus.ui.model.LoginUi
import dev.duardo.neotransfer.plus.ui.model.Money
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.PasswordSettingsUi
import dev.duardo.neotransfer.plus.ui.model.PasswordSheetUi
import dev.duardo.neotransfer.plus.ui.model.PayQrUi
import dev.duardo.neotransfer.plus.ui.model.PayReviewUi
import dev.duardo.neotransfer.plus.ui.model.PaymentResultUi
import dev.duardo.neotransfer.plus.ui.model.ReceiveUi
import dev.duardo.neotransfer.plus.ui.model.RecipientPickUi
import dev.duardo.neotransfer.plus.ui.model.RecipientUi
import dev.duardo.neotransfer.plus.ui.model.RefreshState
import dev.duardo.neotransfer.plus.ui.model.ServiceFormUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import dev.duardo.neotransfer.plus.ui.model.ServiceLookupUi
import dev.duardo.neotransfer.plus.ui.model.ServicesUi
import dev.duardo.neotransfer.plus.ui.model.SettingsUi
import dev.duardo.neotransfer.plus.ui.model.TransferAmountUi
import dev.duardo.neotransfer.plus.ui.model.TransferReviewUi
import dev.duardo.neotransfer.plus.ui.model.WalletCardUi
import dev.duardo.neotransfer.plus.ui.pay.PayQrScreen
import dev.duardo.neotransfer.plus.ui.payment.PasswordSheet
import dev.duardo.neotransfer.plus.ui.payment.PaymentResultScreen
import dev.duardo.neotransfer.plus.ui.receive.QrCode
import dev.duardo.neotransfer.plus.ui.receive.ReceiveScreen
import dev.duardo.neotransfer.plus.ui.services.ServiceFormScreen
import dev.duardo.neotransfer.plus.ui.services.ServicesScreen
import dev.duardo.neotransfer.plus.ui.settings.AboutScreen
import dev.duardo.neotransfer.plus.ui.settings.LicensesScreen
import dev.duardo.neotransfer.plus.ui.settings.PasswordSettingsScreen
import dev.duardo.neotransfer.plus.ui.settings.SettingsScreen
import dev.duardo.neotransfer.plus.ui.shell.MainShell
import dev.duardo.neotransfer.plus.ui.shell.Tab
import dev.duardo.neotransfer.plus.ui.transfer.RecipientScreen
import dev.duardo.neotransfer.plus.ui.transfer.TransferAmountScreen
import dev.duardo.neotransfer.plus.ui.transfer.TransferReviewScreen
import dev.duardo.neotransfer.plus.ui.model.Appearance as Theme

/** Every screen and state, by id. Ids are what `--es screen` takes and how captures are named. */
object PreviewCatalog {
    private val screens: Map<String, @Composable () -> Unit> = linkedMapOf(
        // Access ----------------------------------------------------------------------------------------------
        "lock" to { LockScreen(LockUi.Prompting, noop()) },
        "lock-cancelled" to { LockScreen(LockUi.Cancelled, noop()) },
        "login" to { LoginScreen(LoginUi.Start(), noop(), {}) },
        "login-offline" to { LoginScreen(LoginUi.Start(LoginProblem.Offline), noop(), {}) },
        "login-web" to { LoginScreen(LoginUi.Web(loading = true), noop(), { FakeWebPage() }) },
        "login-connecting" to { LoginScreen(LoginUi.Connecting, noop(), {}) },

        // Home ------------------------------------------------------------------------------------------------
        "home" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 0, PreviewData.inProgress, PreviewData.recent), noop()) } },
        "home-bandec" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 1, emptyList(), PreviewData.recent), noop()) } },
        "home-banmet" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 2, emptyList(), PreviewData.recent), noop()) } },
        "home-refreshing" to {
            Tabbed(Tab.Home) {
                HomeScreen(HomeUi(listOf(wallet[0].copy(refreshing = true), wallet[1], wallet[2]), 0, emptyList(), PreviewData.recent, RefreshState.Refreshing), noop())
            }
        },
        "home-offline" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 0, emptyList(), PreviewData.recent, RefreshState.Failed), noop()) } },
        "home-loading" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(emptyList(), 0, emptyList(), null, RefreshState.Refreshing), noop()) } },
        "home-no-cards" to { Tabbed(Tab.Home) { HomeScreen(HomeUi(emptyList(), 0, emptyList(), emptyList()), noop()) } },
        "home-unknown-balance" to {
            Tabbed(Tab.Home) { HomeScreen(HomeUi(listOf(WalletCardUi(bpa, BalanceUi.Unknown)), 0, emptyList(), emptyList()), noop()) }
        },
        "home-card-complete" to {
            Tabbed(Tab.Home) {
                HomeScreen(HomeUi(wallet, 1, emptyList(), PreviewData.recent), noop(), sheet = CardSheetUi(bandec, "92051299876"), sheetActions = noop())
            }
        },
        "home-card" to {
            Tabbed(Tab.Home) { HomeScreen(HomeUi(wallet, 0, emptyList(), PreviewData.recent), noop(), sheet = CardSheetUi(bpa, ""), sheetActions = noop()) }
        },

        // Activity --------------------------------------------------------------------------------------------
        "activity" to { Tabbed(Tab.Activity) { ActivityScreen(activity(ActivityContent.Items(PreviewData.history)), noop()) } },
        "activity-cards" to {
            Tabbed(Tab.Activity) {
                ActivityScreen(activity(ActivityContent.Items(PreviewData.cardMovements)).copy(source = ActivitySource.Cards, card = bpa.id), noop())
            }
        },
        "activity-search" to {
            Tabbed(Tab.Activity) {
                ActivityScreen(activity(ActivityContent.Items(listOf(PreviewData.confirmedToJuan))).copy(query = "juan", filter = ActivityFilter.Out), noop())
            }
        },
        "activity-loading" to { Tabbed(Tab.Activity) { ActivityScreen(activity(ActivityContent.Loading), noop()) } },
        "activity-empty" to { Tabbed(Tab.Activity) { ActivityScreen(activity(ActivityContent.Empty), noop()) } },
        "operation-confirmed" to { OperationDetailScreen(PreviewData.confirmedToJuan, noop()) },
        "operation-incoming" to { OperationDetailScreen(PreviewData.incomingFromDaniela, noop()) },
        "operation-qr" to { OperationDetailScreen(PreviewData.qrCafe, noop()) },
        "operation-unconfirmed" to { OperationDetailScreen(PreviewData.unconfirmedToYunior, noop()) },
        "operation-rejected" to { OperationDetailScreen(PreviewData.landlineRejected, noop()) },
        "operation-other" to { OperationDetailScreen(PreviewData.nauta, noop()) },
        "operation-card-movement" to { OperationDetailScreen(PreviewData.cardMovements[2], noop()) },

        // Contacts --------------------------------------------------------------------------------------------
        "contacts" to { Tabbed(Tab.Contacts) { ContactsScreen(ContactsUi("", ContactsContent.Items(PreviewData.contacts)), noop()) } },
        "contacts-empty" to { Tabbed(Tab.Contacts) { ContactsScreen(ContactsUi("", ContactsContent.Empty), noop()) } },
        "contact" to { ContactDetailScreen(PreviewData.juan, noop()) },
        "contact-delete" to { ContactDetailScreen(PreviewData.juan, noop(), confirmingDelete = true) },
        "contact-edit" to {
            ContactEditScreen(
                ContactEditUi(
                    PreviewData.juan.id, "Juan Carlos Pérez",
                    listOf(EditableCardUi("jc-1", "9205990144718265", "Salario", "BANDEC"), EditableCardUi("jc-3", "92123331880421", "")),
                    listOf(EditablePhoneUi("p1", "53124578"), EditablePhoneUi("p2", "5441772", error = "Número incompleto")),
                    canSave = false,
                ),
                noop(),
            )
        },

        // Services --------------------------------------------------------------------------------------------
        "services" to { Tabbed(Tab.Services) { ServicesScreen(ServicesUi(PreviewData.recentReferences), noop()) } },
        "services-new" to { Tabbed(Tab.Services) { ServicesScreen(ServicesUi(emptyList()), noop()) } },
        "service-electricity" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.Electricity, "01234567891",
                    lookup = ServiceLookupUi.Bills(
                        "Laura Méndez Soto",
                        listOf(
                            BillUi("b-10", "Octubre 2026", cup("412.35"), paid = false),
                            BillUi("b-09", "Septiembre 2026", cup("387.10"), paid = true),
                        ),
                        selected = "b-10",
                    ),
                    currency = "CUP", cards = wallet, source = 0, canPay = true,
                ),
                noop(),
            )
        },
        "service-electricity-new" to {
            ServiceFormScreen(ServiceFormUi(ServiceKind.Electricity, "", lookup = ServiceLookupUi.None, currency = "CUP", cards = wallet, source = 0, canPay = false), noop())
        },
        "service-loading" to {
            ServiceFormScreen(ServiceFormUi(ServiceKind.Landline, "78302214", lookup = ServiceLookupUi.Loading, currency = "CUP", cards = wallet, source = 0, canPay = false), noop())
        },
        "service-landline" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.Landline, "78302214", lookup = ServiceLookupUi.PhoneBill("Laura Méndez Soto", cup("155.40")),
                    currency = "CUP", cards = wallet, source = 1, canPay = true,
                ),
                noop(),
            )
        },
        "service-nauta" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.Nauta, "laura.mendez",
                    lookup = ServiceLookupUi.TopUp("Laura Méndez Soto", debt = null, min = cup("25.00"), max = cup("2500.00")),
                    amount = "300", currency = "CUP", cards = wallet, source = 0, canPay = true,
                ),
                noop(),
            )
        },
        "service-nauta-home" to {
            ServiceFormScreen(
                ServiceFormUi(
                    ServiceKind.NautaHome, "laura.mendez.hogar",
                    lookup = ServiceLookupUi.TopUp("Laura Méndez Soto", debt = cup("320.00"), min = cup("100.00"), max = null),
                    amount = "", currency = "CUP", cards = wallet, source = 0, canPay = false,
                ),
                noop(),
            )
        },
        "service-failed" to {
            ServiceFormScreen(
                ServiceFormUi(ServiceKind.Electricity, "01234567899", lookup = ServiceLookupUi.Failed("ENZONA no encontró ese código de cliente."), currency = "CUP", cards = wallet, source = 0, canPay = false),
                noop(),
            )
        },

        // Transfer --------------------------------------------------------------------------------------------
        "transfer-recipient" to {
            RecipientScreen(
                RecipientPickUi(
                    "", null, null,
                    recent = listOf(
                        PreviewData.juanRecipient,
                        RecipientUi.Card(PreviewData.maria.cards.first(), PreviewData.maria.name, PreviewData.maria.id),
                        RecipientUi.Card(ForeignCardUi("x", "9212333188042154", "BPA")),
                        RecipientUi.Card(PreviewData.daniela.cards.first(), PreviewData.daniela.name, PreviewData.daniela.id),
                    ),
                    contacts = PreviewData.contacts.filter { it.cards.isNotEmpty() },
                ),
                noop(),
            )
        },
        "transfer-recipient-typed" to {
            RecipientScreen(RecipientPickUi("9205 8800 1234 5673", ForeignCardUi("t", "9205880012345673", "BANDEC"), null, emptyList(), emptyList()), noop())
        },
        "transfer-recipient-cards" to {
            RecipientScreen(RecipientPickUi("", null, null, emptyList(), PreviewData.contacts.filter { it.cards.isNotEmpty() }, chooseCardOf = PreviewData.juan), noop())
        },
        "transfer-amount" to { TransferAmountScreen(TransferAmountUi(PreviewData.juanRecipient, "300", "CUP", wallet, 0, false, true), noop()) },
        "transfer-amount-over" to { TransferAmountScreen(TransferAmountUi(PreviewData.juanRecipient, "4500.5", "CUP", wallet, 1, true, true), noop()) },
        "transfer-amount-account" to {
            TransferAmountScreen(TransferAmountUi(PreviewData.accountRecipient, "750", "CUP", wallet, 2, false, true, fixed = true), noop())
        },
        "transfer-review" to { TransferReviewScreen(TransferReviewUi(PreviewData.juanRecipient, cup("300.00"), bpa, saveContact = null), noop()) },
        "transfer-review-new" to {
            TransferReviewScreen(TransferReviewUi(RecipientUi.Card(ForeignCardUi("t", "9205880012345673", "BANDEC")), cup("1250.00"), bandec, saveContact = true), noop())
        },
        "transfer-review-account" to { TransferReviewScreen(TransferReviewUi(PreviewData.accountRecipient, cup("750.00"), banmet, saveContact = null), noop()) },
        "transfer-password" to {
            TransferReviewScreen(TransferReviewUi(PreviewData.juanRecipient, cup("300.00"), bpa, saveContact = null), noop())
            PasswordSheet(PasswordSheetUi("contraseña", "Guardar y transferir"), noop())
        },
        "payment-sending" to { PaymentResultScreen(PaymentResultUi(PreviewData.sendingToMaria), noop()) },
        "payment-confirmed" to {
            PaymentResultScreen(PaymentResultUi(PreviewData.sendingToMaria.copy(status = OperationStatus.Confirmed, transactionId = "4f1c9a20-7b3e-4d61-8e0a-52c7d9b1a6e3")), noop())
        },
        "payment-unconfirmed" to { PaymentResultScreen(PaymentResultUi(PreviewData.unconfirmedToYunior), noop()) },
        "payment-rejected" to { PaymentResultScreen(PaymentResultUi(PreviewData.landlineRejected), noop()) },

        // Pay and receive -------------------------------------------------------------------------------------
        "pay-scanning" to { PayQrScreen(PayQrUi.Scanning, noop(), { FakeCamera() }) },
        "pay-resolving" to { PayQrScreen(PayQrUi.Resolving, noop(), { FakeCamera(locked = true) }) },
        "pay-review" to {
            PayQrScreen(
                PayQrUi.Review(PayReviewUi("Cafetería La Esquina", "480.00", false, "", "CUP", wallet, 0, canPay = true)),
                noop(), { FakeCamera(locked = true) },
            )
        },
        "pay-review-open-amount" to {
            PayQrScreen(
                PayQrUi.Review(PayReviewUi("Mercado La Plaza", "", true, "Viandas", "CUP", wallet, 1, canPay = false)),
                noop(), { FakeCamera(locked = true) },
            )
        },
        "pay-unsupported" to { PayQrScreen(PayQrUi.Unsupported("Este QR no es de pago"), noop(), { FakeCamera(locked = true) }) },
        "receive" to { ReceiveScreen(ReceiveUi.Amount("1500", "CUP"), noop()) },
        "receive-code" to { ReceiveScreen(ReceiveUi.Code(PreviewData.ownQrPayload, Money(java.math.BigDecimal("1500"), "CUP"), PreviewData.account.name), noop()) },

        // Settings --------------------------------------------------------------------------------------------
        "settings" to { SettingsScreen(SettingsUi(PreviewData.account, passwordSaved = true, Theme.System, notifications = true, version = "0.1.0"), noop()) },
        "settings-sign-out" to {
            SettingsScreen(SettingsUi(PreviewData.account, passwordSaved = false, Theme.Dark, notifications = false, version = "0.1.0", confirmingSignOut = true), noop())
        },
        "settings-password" to { PasswordSettingsScreen(PasswordSettingsUi(saved = false, value = ""), noop()) },
        "settings-password-saved" to { PasswordSettingsScreen(PasswordSettingsUi(saved = true, value = "secreto"), noop()) },
        "settings-about" to { AboutScreen("0.1.0", noop()) },
        "settings-licenses" to {
            LicensesScreen(listOf("Inter" to "SIL Open Font License, Version 1.1\n\nCopyright 2020 The Inter Project Authors."), {})
        },
    )

    val ids: List<String> get() = screens.keys.toList()

    @Composable
    fun content(id: String) {
        screens.getValue(id)()
    }
}

private fun activity(content: ActivityContent) =
    ActivityUi(ActivitySource.Account, "", ActivityFilter.All, listOf(bpa, bandec, banmet), null, content, olderFrom = java.time.LocalDate.of(2026, 10, 7))

@Composable
private fun Tabbed(tab: Tab, content: @Composable () -> Unit) {
    MainShell(tab, {}, content = content)
}

/** Stand-in for ENZONA's sign-in page: its layout without its brand. */
@Composable
private fun FakeWebPage() {
    Column(Modifier.fillMaxSize().background(Color.White).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.size(120.dp, 22.dp).skeleton())
        Box(Modifier.fillMaxWidth().height(52.dp).skeleton(RoundedCornerShape(8.dp)))
        Box(Modifier.fillMaxWidth().height(52.dp).skeleton(RoundedCornerShape(8.dp)))
        Box(Modifier.fillMaxWidth().height(48.dp).skeleton(RoundedCornerShape(24.dp)))
    }
}

/**
 * Stand-in for the scanner: a dim camera feed with a merchant QR, the scanner's brackets (landed when [locked]),
 * its close button and its bottom controls.
 */
@Composable
private fun FakeCamera(locked: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A3530), Color(0xFF1C1A18), Color(0xFF0E0D0C))))) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(236.dp)
                .graphicsLayer { rotationZ = -3f }
                .background(Color(0xFFF2F0EC), RoundedCornerShape(12.dp))
                .padding(22.dp),
        ) { QrCode("07CLE00417PAGO", Modifier.fillMaxSize(), Color(0xFF1A1A1A)) }
        val frame = MaterialTheme.colorScheme.primary.takeIf { locked } ?: Color.White
        Box(
            Modifier.align(Alignment.Center).size(if (locked) 214.dp else 260.dp).drawBehind {
                val stroke = 4.dp.toPx()
                val arm = 34.dp.toPx()
                val r = 18.dp.toPx()
                val w = size.width
                val h = size.height
                listOf(Triple(Offset(0f, 0f), 1f, 1f), Triple(Offset(w, 0f), -1f, 1f), Triple(Offset(0f, h), 1f, -1f), Triple(Offset(w, h), -1f, -1f))
                    .forEach { (o, dx, dy) ->
                        val path = Path().apply {
                            moveTo(o.x, o.y + dy * arm)
                            lineTo(o.x, o.y + dy * r)
                            quadraticTo(o.x, o.y, o.x + dx * r, o.y)
                            lineTo(o.x + dx * arm, o.y)
                        }
                        drawPath(path, frame, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
            },
        )
        CameraButton(Ds.drawable.ic_close, Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), 48)
        if (!locked) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp).padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraButton(Ds.drawable.ic_photo_library)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("1×", "2×").forEachIndexed { index, label ->
                        Box(
                            Modifier.size(40.dp).background(if (index == 0) Color.White.copy(alpha = 0.18f) else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            CameraButton(Ds.drawable.ic_flashlight_off)
        }
    }
}

@Composable
private fun CameraButton(icon: Int, modifier: Modifier = Modifier, size: Int = 56) {
    Box(modifier.size(size.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = Color.White)
    }
}
