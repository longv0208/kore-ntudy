package com.ksh.features.admin.settings.controller;

import com.ksh.features.admin.settings.dto.PracticeAiSettingsDtos.BindingForm;
import com.ksh.features.admin.settings.dto.PracticeAiSettingsDtos.CapabilityTestResult;
import com.ksh.features.admin.settings.dto.PracticeAiSettingsDtos.ProfileForm;
import com.ksh.features.admin.settings.service.PracticeAiControlPlaneAdminService;
import com.ksh.features.practice.ai.controlplane.PracticeAiCapabilityTestService;
import com.ksh.features.practice.ai.controlplane.PracticeAiControlPlaneException;
import com.ksh.features.practice.ai.controlplane.PracticeDirectAudioCapabilityRegistry;
import com.ksh.features.practice.ai.controlplane.PracticeAiFixedProviderPresetRegistry;
import com.ksh.features.practice.ai.controlplane.PracticeAiPurpose;
import com.ksh.security.KshUserDetails;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

import static com.ksh.common.IConstant.ATTR_ACTIVE_TAB;
import static com.ksh.common.IConstant.ATTR_FLASH_ERROR;
import static com.ksh.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ksh.common.IConstant.TAB_SETTINGS;

@Controller
@RequestMapping("/admin/settings/practice-ai")
@PreAuthorize("hasAuthority('PERM_system.ai')")
public class PracticeAiControlPlaneController {

    private static final String REDIRECT = "redirect:/admin/settings/practice-ai";

    private final PracticeAiControlPlaneAdminService adminService;
    private final PracticeAiCapabilityTestService capabilityTestService;

    public PracticeAiControlPlaneController(
            PracticeAiControlPlaneAdminService adminService,
            PracticeAiCapabilityTestService capabilityTestService) {
        this.adminService = adminService;
        this.capabilityTestService = capabilityTestService;
    }

    @GetMapping
    public String list(Model model) {
        populateList(model);
        return "admin/settings-practice-ai";
    }

    @GetMapping("/profiles/new")
    public String newProfile(Model model) {
        model.addAttribute("form", ProfileForm.empty());
        populateProfileForm(model, "create");
        return "admin/settings-practice-ai-profile-form";
    }

    @GetMapping("/profiles/{id}/edit")
    public String editProfile(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirect) {
        return adminService.profileForm(id)
                .map(form -> {
                    model.addAttribute("form", form);
                    populateProfileForm(model, "edit");
                    return "admin/settings-practice-ai-profile-form";
                })
                .orElseGet(() -> errorRedirect(redirect, "PROFILE_NOT_FOUND"));
    }

    @PostMapping("/profiles")
    public String saveProfile(
            @Valid @ModelAttribute("form") ProfileForm form,
            BindingResult result,
            @AuthenticationPrincipal KshUserDetails principal,
            Model model,
            RedirectAttributes redirect) {
        if (principal == null) {
            return errorRedirect(redirect, "ADMIN_SESSION_UNSUPPORTED");
        }
        if (form.id() == null
                && "STATIC_BEARER".equals(form.credentialMode())
                && (form.credentialSecret() == null
                || form.credentialSecret().isBlank())) {
            result.rejectValue(
                    "credentialSecret", "required",
                    "Vui lòng nhập API key / credential");
        }
        if (result.hasErrors()) {
            populateProfileForm(model, form.id() == null ? "create" : "edit");
            return "admin/settings-practice-ai-profile-form";
        }
        try {
            boolean creating = form.id() == null;
            Long savedId = adminService.saveProfile(form, principal.getId());
            redirect.addFlashAttribute(
                    ATTR_FLASH_SUCCESS,
                    creating
                            ? "Đã lưu nhà cung cấp. Tiếp theo, hãy chọn model cho mục đích đầu tiên."
                            : "Đã lưu thay đổi nhà cung cấp Practice AI.");
            if (creating) {
                PracticeAiPurpose firstPurpose = isDirectAudioProfile(form)
                        ? PracticeAiPurpose.PRACTICE_SPEAKING_DIRECT_AUDIO_EVALUATION
                        : PracticeAiPurpose.PRACTICE_PDF_AUTHORING;
                return "redirect:/admin/settings/practice-ai/bindings/"
                        + firstPurpose.name()
                        + "/edit?profileId=" + savedId;
            }
            return REDIRECT;
        } catch (RuntimeException exception) {
            result.reject("profile", safeCode(exception));
            populateProfileForm(model, form.id() == null ? "create" : "edit");
            return "admin/settings-practice-ai-profile-form";
        }
    }

    @PostMapping("/profiles/presets/{presetKey}")
    public String createFixedProviderPreset(
            @PathVariable String presetKey,
            @AuthenticationPrincipal KshUserDetails principal,
            RedirectAttributes redirect) {
        if (principal == null) {
            return errorRedirect(redirect, "ADMIN_SESSION_UNSUPPORTED");
        }
        try {
            Long profileId = adminService.createFixedProviderPreset(
                    presetKey, principal.getId());
            redirect.addFlashAttribute(
                    ATTR_FLASH_SUCCESS,
                    "Đã tạo profile tắt. Nhập key khi sẵn sàng; model vẫn cần kiểm tra riêng.");
            return "redirect:/admin/settings/practice-ai/profiles/"
                    + profileId + "/edit";
        } catch (RuntimeException exception) {
            return errorRedirect(redirect, safeCode(exception));
        }
    }

    @PostMapping("/profiles/{id}/toggle")
    public String toggleProfile(
            @PathVariable Long id,
            @AuthenticationPrincipal KshUserDetails principal,
            RedirectAttributes redirect) {
        if (principal == null) {
            return errorRedirect(redirect, "ADMIN_SESSION_UNSUPPORTED");
        }
        try {
            adminService.toggleProfile(id, principal.getId());
            redirect.addFlashAttribute(ATTR_FLASH_SUCCESS, "Practice AI profile updated");
            return REDIRECT;
        } catch (RuntimeException exception) {
            return errorRedirect(redirect, safeCode(exception));
        }
    }

    @PostMapping("/profiles/{id}/delete")
    public String deleteProfile(
            @PathVariable Long id,
            RedirectAttributes redirect) {
        try {
            adminService.deleteProfile(id);
            redirect.addFlashAttribute(ATTR_FLASH_SUCCESS, "Unbound profile deleted");
            return REDIRECT;
        } catch (RuntimeException exception) {
            return errorRedirect(redirect, safeCode(exception));
        }
    }

    @GetMapping(value = "/profiles/{id}/secret", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> revealSecret(@PathVariable Long id) {
        Map<String, Object> body = adminService.revealSecret(id)
                .<Map<String, Object>>map(secret -> Map.of("ok", true, "secret", secret))
                .orElseGet(() -> Map.of("ok", false, "errorCode", "PROFILE_NOT_FOUND"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @GetMapping("/bindings/{purpose}/edit")
    public String editBinding(
            @PathVariable PracticeAiPurpose purpose,
            @RequestParam(required = false) Long profileId,
            Model model) {
        var profiles = adminService.profiles();
        BindingForm form = adminService.bindingForm(purpose);
        if (form.providerProfileId() == null
                && profileId != null
                && profiles.stream().anyMatch(profile -> profile.id().equals(profileId))) {
            form = form.withProviderProfileId(profileId);
        }
        model.addAttribute("form", form);
        model.addAttribute("profiles", profiles);
        model.addAttribute("purpose", purpose);
        model.addAttribute("requiredCapabilities",
                purpose.requiredCapabilities().stream().sorted()
                        .collect(java.util.stream.Collectors.joining(", ")));
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
        return "admin/settings-practice-ai-binding-form";
    }

    @PostMapping("/bindings/{purpose}")
    public String saveBinding(
            @PathVariable PracticeAiPurpose purpose,
            @Valid @ModelAttribute("form") BindingForm form,
            BindingResult result,
            @AuthenticationPrincipal KshUserDetails principal,
            Model model,
            RedirectAttributes redirect) {
        if (principal == null) {
            return errorRedirect(redirect, "ADMIN_SESSION_UNSUPPORTED");
        }
        if (form.purpose() != purpose) {
            result.reject("purpose", "BINDING_PURPOSE_MISMATCH");
        }
        if (result.hasErrors()) {
            model.addAttribute("form", form);
            model.addAttribute(
                    BindingResult.MODEL_KEY_PREFIX + "form", result);
            model.addAttribute("profiles", adminService.profiles());
            model.addAttribute("purpose", purpose);
            model.addAttribute("requiredCapabilities",
                    purpose.requiredCapabilities().stream().sorted()
                            .collect(java.util.stream.Collectors.joining(", ")));
            model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
            return "admin/settings-practice-ai-binding-form";
        }
        try {
            adminService.saveBinding(form, principal.getId());
            redirect.addFlashAttribute(ATTR_FLASH_SUCCESS, "Practice AI binding saved");
            return REDIRECT;
        } catch (RuntimeException exception) {
            model.addAttribute("form", form);
            result.reject("binding", safeCode(exception));
            model.addAttribute(
                    BindingResult.MODEL_KEY_PREFIX + "form", result);
            model.addAttribute("profiles", adminService.profiles());
            model.addAttribute("purpose", purpose);
            model.addAttribute("requiredCapabilities",
                    purpose.requiredCapabilities().stream().sorted()
                            .collect(java.util.stream.Collectors.joining(", ")));
            model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
            return "admin/settings-practice-ai-binding-form";
        }
    }

    @PostMapping("/bindings/{purpose}/toggle")
    public String toggleBinding(
            @PathVariable PracticeAiPurpose purpose,
            @AuthenticationPrincipal KshUserDetails principal,
            RedirectAttributes redirect) {
        if (principal == null) {
            return errorRedirect(redirect, "ADMIN_SESSION_UNSUPPORTED");
        }
        try {
            adminService.toggleBinding(purpose, principal.getId());
            redirect.addFlashAttribute(ATTR_FLASH_SUCCESS, "Practice AI binding updated");
            return REDIRECT;
        } catch (RuntimeException exception) {
            return errorRedirect(redirect, safeCode(exception));
        }
    }

    @PostMapping(
            value = "/bindings/{purpose}/test",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public CapabilityTestResult testBinding(
            @PathVariable PracticeAiPurpose purpose,
            @AuthenticationPrincipal KshUserDetails principal) {
        if (principal == null) {
            return new CapabilityTestResult(
                    false, "CANCELLED", "ADMIN_SESSION_UNSUPPORTED", null, 0);
        }
        try {
            return capabilityTestService.test(purpose, principal.getId());
        } catch (PracticeAiControlPlaneException exception) {
            return new CapabilityTestResult(
                    false, "FAIL", exception.errorCode(), null, 0);
        }
    }

    private void populateList(Model model) {
        var profiles = adminService.profiles();
        var bindings = adminService.bindings();
        long enabledProfileCount = profiles.stream().filter(profile -> profile.enabled()).count();
        long configuredBindingCount = bindings.stream().filter(binding -> binding.configured()).count();
        long enabledBindingCount = bindings.stream()
                .filter(binding -> binding.configured() && binding.enabled()).count();
        var nextBinding = bindings.stream()
                .filter(binding -> !binding.configured() || !binding.enabled())
                .findFirst().orElse(null);

        model.addAttribute("profiles", profiles);
        model.addAttribute(
                "fixedProviderPresets",
                adminService.fixedProviderPresets(profiles));
        model.addAttribute("bindings", bindings);
        model.addAttribute("enabledProfileCount", enabledProfileCount);
        model.addAttribute("configuredBindingCount", configuredBindingCount);
        model.addAttribute("enabledBindingCount", enabledBindingCount);
        model.addAttribute("purposeCount", bindings.size());
        model.addAttribute("nextBinding", nextBinding);
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
    }

    private void populateProfileForm(Model model, String mode) {
        Object form = model.getAttribute("form");
        if (form instanceof ProfileForm profileForm) {
            model.addAttribute(
                    "fixedPreset",
                    PracticeAiFixedProviderPresetRegistry
                            .findByProfileCode(profileForm.profileCode())
                            .orElse(null));
        }
        model.addAttribute("mode", mode);
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
    }

    private static String errorRedirect(
            RedirectAttributes redirect,
            String errorCode) {
        redirect.addFlashAttribute(ATTR_FLASH_ERROR, errorCode);
        return REDIRECT;
    }

    private static String safeCode(RuntimeException exception) {
        String value = exception.getMessage();
        return value != null && value.matches("[A-Z][A-Z0-9_]{1,63}")
                ? value
                : "PRACTICE_AI_CONTROL_PLANE_ERROR";
    }

    private static boolean isDirectAudioProfile(ProfileForm form) {
        String code = form.profileCode() == null ? "" : form.profileCode().trim();
        return PracticeDirectAudioCapabilityRegistry.GEMINI_DEVELOPER_CODE.equals(code)
                || PracticeDirectAudioCapabilityRegistry.GEMINI_ENTERPRISE_CODE.equals(code);
    }
}
