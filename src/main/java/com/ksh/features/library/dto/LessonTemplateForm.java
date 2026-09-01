package com.ksh.features.library.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/** Authoring payload for a canonical subject lesson in Library. */
public class LessonTemplateForm {

    private Long id;
    private Long subjectId;
    private boolean newChapter;

    private int chapterNumber = 1;

    @NotBlank(message = "Tên chương không được để trống")
    @Size(max = 200, message = "Tên chương tối đa 200 ký tự")
    private String chapterTitle = "Chương 1";

    private int lessonNumber = 1;

    @NotBlank(message = "Tên bài học không được để trống")
    @Size(max = 300, message = "Tên bài học tối đa 300 ký tự")
    private String title;

    @NotBlank(message = "Vui lòng chọn loại nội dung")
    private String contentType = "RICHTEXT";

    private String contentRichtext;
    private Long pdfLibraryAssetId;
    private String videoProvider;
    private String videoUrl;

    @Size(max = 1000, message = "Tóm tắt video tối đa 1000 ký tự")
    private String videoSummary;

    private Long videoLibraryAssetId;
    private List<Long> materialAssetIds = new ArrayList<>();
    private MultipartFile pdfUpload;
    private MultipartFile videoUpload;
    private List<MultipartFile> materialUploads = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }
    public boolean isNewChapter() { return newChapter; }
    public void setNewChapter(boolean newChapter) { this.newChapter = newChapter; }
    public int getChapterNumber() { return chapterNumber; }
    public void setChapterNumber(int chapterNumber) { this.chapterNumber = chapterNumber; }
    public String getChapterTitle() { return chapterTitle; }
    public void setChapterTitle(String chapterTitle) { this.chapterTitle = chapterTitle; }
    public int getLessonNumber() { return lessonNumber; }
    public void setLessonNumber(int lessonNumber) { this.lessonNumber = lessonNumber; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getContentRichtext() { return contentRichtext; }
    public void setContentRichtext(String contentRichtext) { this.contentRichtext = contentRichtext; }
    public Long getPdfLibraryAssetId() { return pdfLibraryAssetId; }
    public void setPdfLibraryAssetId(Long pdfLibraryAssetId) { this.pdfLibraryAssetId = pdfLibraryAssetId; }
    public String getVideoProvider() { return videoProvider; }
    public void setVideoProvider(String videoProvider) { this.videoProvider = videoProvider; }
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
    public String getVideoSummary() { return videoSummary; }
    public void setVideoSummary(String videoSummary) { this.videoSummary = videoSummary; }
    public Long getVideoLibraryAssetId() { return videoLibraryAssetId; }
    public void setVideoLibraryAssetId(Long videoLibraryAssetId) { this.videoLibraryAssetId = videoLibraryAssetId; }
    public List<Long> getMaterialAssetIds() { return materialAssetIds; }
    public void setMaterialAssetIds(List<Long> materialAssetIds) {
        this.materialAssetIds = materialAssetIds == null ? new ArrayList<>() : materialAssetIds;
    }
    public MultipartFile getPdfUpload() { return pdfUpload; }
    public void setPdfUpload(MultipartFile pdfUpload) { this.pdfUpload = pdfUpload; }
    public MultipartFile getVideoUpload() { return videoUpload; }
    public void setVideoUpload(MultipartFile videoUpload) { this.videoUpload = videoUpload; }
    public List<MultipartFile> getMaterialUploads() { return materialUploads; }
    public void setMaterialUploads(List<MultipartFile> materialUploads) {
        this.materialUploads = materialUploads == null ? new ArrayList<>() : materialUploads;
    }
}
