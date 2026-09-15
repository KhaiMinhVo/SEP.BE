package com.influencermatch.backend.controller;
import com.influencermatch.backend.dto.*; import com.influencermatch.backend.dto.auth.*; import com.influencermatch.backend.service.AdminUserService; import jakarta.validation.Valid; import lombok.RequiredArgsConstructor; import org.springframework.data.domain.PageRequest; import org.springframework.web.bind.annotation.*; import java.util.UUID;
@RestController @RequestMapping("/admin/users") @RequiredArgsConstructor public class AdminUserController {
    private final AdminUserService service;
    @GetMapping public ApiResponse<PageResponse<AdminUserResponse>> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return ApiResponse.ok(PageResponse.from(service.list(PageRequest.of(Math.max(page,0),Math.min(Math.max(size,1),100)))));}
    @GetMapping("/{id}") public ApiResponse<AdminUserResponse> get(@PathVariable UUID id){return ApiResponse.ok(service.get(id));}
    @PatchMapping("/{id}/status") public ApiResponse<AdminUserResponse> status(@PathVariable UUID id,@Valid @RequestBody UpdateUserStatusRequest request){return ApiResponse.ok(service.changeStatus(id,request));}
}
