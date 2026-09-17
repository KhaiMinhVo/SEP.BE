package com.influencermatch.backend.user;
import com.influencermatch.backend.auth.RefreshTokenService;
import com.influencermatch.backend.auth.dto.*; import com.influencermatch.backend.user.*; import com.influencermatch.backend.exception.NotFoundException; import com.influencermatch.backend.user.UserRepository;
import lombok.RequiredArgsConstructor; import org.springframework.data.domain.*; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional; import java.time.LocalDateTime; import java.util.UUID;
@Service @RequiredArgsConstructor public class AdminUserService {
    private final UserRepository users; private final RefreshTokenService refreshTokens;
    @Transactional(readOnly=true) public Page<AdminUserResponse> list(Pageable pageable){return users.findAll(pageable).map(AdminUserResponse::from);}
    @Transactional(readOnly=true) public AdminUserResponse get(UUID id){return AdminUserResponse.from(find(id));}
    @Transactional public AdminUserResponse changeStatus(UUID id,UpdateUserStatusRequest request){User user=find(id);user.setStatus(request.status());if(request.status()!=UserStatus.ACTIVE)refreshTokens.revokeAll(user.getId());return AdminUserResponse.from(user);}
    private User find(UUID id){return users.findById(id).orElseThrow(()->new NotFoundException("User not found"));}
}


