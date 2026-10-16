// Copyright 2026 Internet Mastering & Company, Inc.
// SPDX-License-Identifier: Apache-2.0

package com.rostamvpn.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.R
import com.rostamvpn.android.ui.theme.Brand1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val operator = stringResource(R.string.legal_operator)

    val operatorEnd = operator.trimEnd('.') + "."
    val product = stringResource(R.string.app_name)
    val email = stringResource(R.string.legal_email)
    val site = stringResource(R.string.legal_site)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.privacy_policy),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Brand1
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = Brand1
                    )
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = Color.Transparent
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.effective_date),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            PolicyText(
                "$operator (\u201Cus\u201D, \u201Cwe\u201D, or \u201Cour\u201D) operates the $product mobile " +
                "application (hereinafter referred to as the \u201CService\u201D).\n\n" +
                "This page informs you of our policies regarding the collection, use and disclosure " +
                "of unidentifiable personal data when you use our Service and the choices you have " +
                "associated with that data.\n\n" +
                "We use this data to provide and improve the Service. By using the Service, you agree " +
                "to the collection and use of information in accordance with this policy. Unless otherwise " +
                "defined in this Privacy Policy, the terms used in this Privacy Policy have the same " +
                "meanings as in our Terms and Conditions."
            )

            SectionHeader("Definitions")

            SubSectionHeader("Service")
            PolicyText("Service is the $product mobile application operated by $operatorEnd")

            SubSectionHeader("Unidentifiable Personal Data")
            PolicyText(
                "Unidentifiable Personal Data means data about a living individual who cannot be " +
                "identified from those data (or from those and other information either in our " +
                "possession or likely to come into our possession)."
            )

            SubSectionHeader("Usage Data")
            PolicyText(
                "Usage Data is data collected automatically either generated by the use of the " +
                "Service or from the Service infrastructure itself (for example, the duration of a page visit)."
            )

            SectionHeader("Information Collection and Use")
            PolicyText(
                "We collect below information for various purposes to provide and improve our Service to you."
            )

            SubSectionHeader("Types of Data Collected")
            PolicyText(
                "When you access the Service with a mobile device, we may collect certain information " +
                "automatically, including, but not limited to, the type of mobile device you use, your " +
                "mobile device unique ID, your mobile operating system, unique device identifiers, your " +
                "mobile carrier, country, and other diagnostic data (\u201CUsage Data\u201D)."
            )

            SectionHeader("Use of Data")
            PolicyText(
                "$operator uses the collected data for various purposes:\n\n" +
                "\u2022 To provide and maintain our Service\n" +
                "\u2022 To notify you about changes to our Service\n" +
                "\u2022 To allow you to participate in interactive features of our Service when you choose to do so\n" +
                "\u2022 To gather analysis or valuable information so that we can improve our Service\n" +
                "\u2022 To monitor the usage of our Service\n" +
                "\u2022 To detect, prevent and address technical issues"
            )

            SectionHeader("Transfer of Data")
            PolicyText(
                "Your information, including Personal Data, may be transferred to \u2014 and maintained " +
                "on \u2014 computers located outside of your state, province, country or other governmental " +
                "jurisdiction where the data protection laws may differ from those of your jurisdiction.\n\n" +
                "Your consent to this Privacy Policy followed by your submission of such information " +
                "represents your agreement to that transfer.\n\n" +
                "$operator will take all the steps reasonably necessary to ensure that your data is " +
                "treated securely and in accordance with this Privacy Policy and no transfer of your " +
                "Personal Data will take place to an organisation or a country unless there are adequate " +
                "controls in place including the security of your data and other personal information."
            )

            SectionHeader("Disclosure of Data")

            SubSectionHeader("Disclosure for Law Enforcement")
            PolicyText(
                "Under certain circumstances, $operator may be required to disclose your Personal " +
                "Data if required to do so by law or in response to valid requests by public authorities " +
                "(e.g. a court or a government agency)."
            )

            SubSectionHeader("Legal Requirements")
            PolicyText(
                "$operator may disclose your Personal Data in the good faith belief that such action " +
                "is necessary to:\n\n" +
                "\u2022 To comply with a legal obligation\n" +
                "\u2022 To protect and defend the rights or property of $operator\n" +
                "\u2022 To prevent or investigate possible wrongdoing in connection with the Service\n" +
                "\u2022 To protect the personal safety of users of the Service or the public\n" +
                "\u2022 To protect against legal liability"
            )

            SectionHeader("Security of Data")
            PolicyText(
                "The security of your data is important to us but remember that no method of " +
                "transmission over the Internet or method of electronic storage is 100% secure. " +
                "While we strive to use commercially acceptable means to protect your Personal Data, " +
                "we cannot guarantee its absolute security."
            )

            SectionHeader("Service Providers")
            PolicyText(
                "We may employ third party companies and individuals to facilitate our Service " +
                "(\u201CService Providers\u201D), provide the Service on our behalf, perform Service-related " +
                "services or assist us in analysing how our Service is used.\n\n" +
                "These third parties have access to your Personal Data only to perform these tasks " +
                "on our behalf and are obligated not to disclose or use it for any other purpose."
            )

            SectionHeader("Analytics")
            PolicyText(
                "We may use third-party Service Providers to monitor and analyze the use of our Service."
            )

            SectionHeader("Changes to This Privacy Policy")
            PolicyText(
                "We may update our Privacy Policy from time to time. We will notify you of any changes " +
                "by posting the new Privacy Policy on this page.\n\n" +
                "We will let you know via email and/or a prominent notice on our Service, prior to the " +
                "change becoming effective and update the \u201Ceffective date\u201D at the top of this Privacy Policy.\n\n" +
                "You are advised to review this Privacy Policy periodically for any changes. Changes to " +
                "this Privacy Policy are effective when they are posted on this page."
            )

            val contact = listOfNotNull(
                email.takeIf { it.isNotBlank() }?.let { "\u2022 By email: $it" },
                site.takeIf { it.isNotBlank() }?.let { "\u2022 By visiting: $it" },
            )
            if (contact.isNotEmpty()) {
                SectionHeader("Contact Us")
                PolicyText(
                    "If you have any questions about this Privacy Policy, please contact us:\n\n" +
                        contact.joinToString("\n")
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Brand1,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SubSectionHeader(text: String) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun PolicyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth()
    )
}
