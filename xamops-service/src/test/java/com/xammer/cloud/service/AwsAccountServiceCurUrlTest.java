package com.xammer.cloud.service;

import com.xammer.cloud.domain.AwsBillingSource;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sts.StsClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two-stack onboarding split: the connection stack is created in ap-south-1 and no longer
 * carries an EnableCurReport parameter, while a standalone CUR report stack is launched in
 * us-east-1 only for accounts that own their report (OWN_CUR). Built via the positional
 * constructor + ReflectionTestUtils for the {@code @Value} fields, following
 * {@link AwsAccountServiceDeleteAccountTest}.
 */
class AwsAccountServiceCurUrlTest {

    private static final String CONN_TEMPLATE = "https://s3.example/xamops-readonly-role.yml";
    private static final String CONN_RW_TEMPLATE = "https://s3.example/xamops-readwrite-role.yml";
    private static final String CUR_TEMPLATE = "https://s3.example/xamops-cur-report.yml";

    private final CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);
    private final ClientRepository clientRepository = mock(ClientRepository.class);

    private AwsAccountService service;

    @BeforeEach
    void setUp() {
        StsClient stsClient = mock(StsClient.class);
        service = new AwsAccountService(cloudAccountRepository, clientRepository,
                mock(AwsClientProvider.class), stsClient, mock(CacheService.class),
                mock(ApplicationEventPublisher.class),
                mock(com.xammer.cloud.service.cur.CurAutoRegistrationService.class));

        ReflectionTestUtils.setField(service, "cloudFormationTemplateUrl", CONN_TEMPLATE);
        ReflectionTestUtils.setField(service, "cloudFormationReadWriteTemplateUrl", CONN_RW_TEMPLATE);
        ReflectionTestUtils.setField(service, "curTemplateUrl", CUR_TEMPLATE);
        ReflectionTestUtils.setField(service, "readOnlyRoleName", "XamOpsReadOnlyRole");
        ReflectionTestUtils.setField(service, "readWriteRoleName", "XamOpsReadWriteRole");

        when(clientRepository.findById(anyLong())).thenReturn(Optional.of(mock(Client.class)));
        when(cloudAccountRepository.save(any(CloudAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void connectionUrl_isApSouth1_andNeverCarriesEnableCurReport() throws Exception {
        Map<String, Object> result = service.generateCloudFormationUrl(
                "Acme Prod", "111122223333", "read-only", 1L, "cur-bucket-1", "OWN_CUR");

        String url = result.get("url").toString();
        assertThat(url).contains("region=ap-south-1");
        assertThat(url).doesNotContain("EnableCurReport");
        // CurS3Bucket is still passed to the connection stack when provided.
        assertThat(url).contains("param_CurS3Bucket=cur-bucket-1");
    }

    @Test
    void curUrl_absentForOwnCur_becauseCurStackIsAutoCreatedOnConnect() throws Exception {
        // XamOps creates the CUR stack itself after the connection stack completes, so no
        // manual launch link is handed to the customer.
        Map<String, Object> result = service.generateCloudFormationUrl(
                "Acme Prod", "111122223333", "read-only", 1L, "cur-bucket-1", "OWN_CUR");

        assertThat(result).doesNotContainKey("curUrl");
        assertThat(result).doesNotContainKey("curStackName");
        assertThat(result).doesNotContainKey("curRegion");
    }

    @Test
    void curUrl_absentForNonOwnCur() throws Exception {
        Map<String, Object> result = service.generateCloudFormationUrl(
                "Acme Member", "111122223333", "read-only", 1L, null, "XAMMER_MPA_CUR");

        assertThat(result).doesNotContainKey("curUrl");
        assertThat(result).doesNotContainKey("curStackName");
        assertThat(result).doesNotContainKey("curRegion");
    }

    @Test
    void ownCurAccount_persistsCurStackIdentity() throws Exception {
        service.generateCloudFormationUrl(
                "Acme Prod", "111122223333", "read-only", 1L, "cur-bucket-1", "OWN_CUR");

        ArgumentCaptor<CloudAccount> captor = ArgumentCaptor.forClass(CloudAccount.class);
        verify(cloudAccountRepository, atLeastOnce()).save(captor.capture());
        CloudAccount saved = captor.getValue();
        assertThat(saved.getCurStackName()).isEqualTo("XamOps-CUR-AcmeProd");
        assertThat(saved.getCurStackRegion()).isEqualTo("us-east-1");
    }

    @Test
    void nonOwnCurAccount_leavesCurStackIdentityNull() throws Exception {
        service.generateCloudFormationUrl(
                "Acme Member", "111122223333", "read-only", 1L, null, "XAMMER_MPA_CUR");

        ArgumentCaptor<CloudAccount> captor = ArgumentCaptor.forClass(CloudAccount.class);
        verify(cloudAccountRepository, atLeastOnce()).save(captor.capture());
        CloudAccount saved = captor.getValue();
        assertThat(saved.getCurStackName()).isNull();
        assertThat(saved.getCurStackRegion()).isNull();
    }

    @Test
    void updateGuide_keepsTopLevelKeys_dropsTwoPassWarning_addsCurSection_forOwnCur() {
        CloudAccount account = new CloudAccount("Acme Prod", "ext-1", "read-only", mock(Client.class));
        account.setAwsBillingSource(AwsBillingSource.OWN_CUR);

        Map<String, Object> guide = service.generateStackUpdateGuide(account);

        assertThat(guide).containsKeys("templateUrl", "consoleUrl", "likelyStackName",
                "roleName", "parameters");
        assertThat(guide).doesNotContainKey("twoPassWarning");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> parameters = (List<Map<String, String>>) guide.get("parameters");
        assertThat(parameters).noneMatch(p -> "EnableCurReport".equals(p.get("name")));
        assertThat(parameters).noneMatch(p -> "EnableCurExport".equals(p.get("name")));

        assertThat(guide).containsKey("cur");
        @SuppressWarnings("unchecked")
        Map<String, Object> cur = (Map<String, Object>) guide.get("cur");
        assertThat(cur.get("region")).isEqualTo("us-east-1");
        assertThat(cur.get("applicable")).isEqualTo("true");
        assertThat(cur.get("likelyStackName")).isEqualTo("XamOps-CUR-AcmeProd");
    }

    @Test
    void updateGuide_curSectionApplicable_regardlessOfBillingSource() {
        // The CUR section is now shown for every account, including ones not flagged OWN_CUR
        // (e.g. an MPA member, or a pre-existing account whose billing source is still null).
        // The operator decides whether to apply it; the backend no longer hides the row.
        CloudAccount account = new CloudAccount("Acme Member", "ext-2", "read-only", mock(Client.class));
        account.setAwsBillingSource(AwsBillingSource.XAMMER_MPA_CUR);

        Map<String, Object> guide = service.generateStackUpdateGuide(account);

        @SuppressWarnings("unchecked")
        Map<String, Object> cur = (Map<String, Object>) guide.get("cur");
        assertThat(cur.get("applicable")).isEqualTo("true");
    }

    @Test
    void updateGuide_curSectionApplicable_forNullBillingSource() {
        // A pre-existing account with no billing source set still gets the CUR row.
        CloudAccount account = new CloudAccount("Legacy Acct", "ext-3", "read-only", mock(Client.class));

        Map<String, Object> guide = service.generateStackUpdateGuide(account);

        @SuppressWarnings("unchecked")
        Map<String, Object> cur = (Map<String, Object>) guide.get("cur");
        assertThat(cur.get("applicable")).isEqualTo("true");
    }
}
