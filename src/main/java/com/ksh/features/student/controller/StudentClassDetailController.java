package com.ksh.features.student.controller;

import com.ksh.features.classes.service.ClassMaterialsService;
import com.ksh.features.student.service.StudentClassDetailService;
import com.ksh.security.KshUserDetails;
import com.ksh.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/** Student class overview pages sharing one flat class-detail shell. */
@Controller
@RequestMapping("/my/classes/{classId}")
@PreAuthorize(Roles.PREAUTH_STUDENT)
public class StudentClassDetailController {

    private final StudentClassDetailService detailService;
    private final ClassMaterialsService materialsService;

    public StudentClassDetailController(StudentClassDetailService detailService,
                                        ClassMaterialsService materialsService) {
        this.detailService = detailService;
        this.materialsService = materialsService;
    }

    @GetMapping
    public String root(@PathVariable Long classId) {
        return "redirect:/my/classes/" + classId + "/lessons";
    }

    @GetMapping("/board")
    public String board(@PathVariable Long classId) {
        return "redirect:/my/classes/" + classId + "/lessons";
    }

    @GetMapping("/members")
    public String members(@PathVariable Long classId,
                          @AuthenticationPrincipal KshUserDetails user,
                          Model model) {
        model.addAttribute("view", detailService.get(classId, user.getId()));
        return "student/class-members";
    }

    @GetMapping("/materials")
    public String materials(@PathVariable Long classId,
                            @AuthenticationPrincipal KshUserDetails user,
                            Model model) {
        model.addAttribute("view", detailService.get(classId, user.getId()));
        model.addAttribute("classMaterials",
                materialsService.listForStudent(classId, user.getId()));
        return "student/class-materials";
    }
}
