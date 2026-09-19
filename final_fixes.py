import os

# Delete RelationshipEnums.java
enums_path = r"d:\SEP_BE\src\main\java\com\influencermatch\backend\relationship\enums\RelationshipEnums.java"
if os.path.exists(enums_path):
    os.remove(enums_path)

# Fix AdminUserService.java
admin_user_service_path = r"d:\SEP_BE\src\main\java\com\influencermatch\backend\user\service\AdminUserService.java"
with open(admin_user_service_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("import com.influencermatch.backend.user.*;", "")
content = content.replace("import com.influencermatch.backend.user.UserRepository;", "import com.influencermatch.backend.user.repository.UserRepository;")

with open(admin_user_service_path, 'w', encoding='utf-8') as f:
    f.write(content)

# Update RelationshipStage.java
stage_path = r"d:\SEP_BE\src\main\java\com\influencermatch\backend\relationship\enums\RelationshipStage.java"
stage_content = """package com.influencermatch.backend.relationship.enums;

public enum RelationshipStage {
    SHORTLISTED, CONTACTED, REPLIED, INTERESTED, COLLABORATING, NOT_INTERESTED, COMPLETED
}
"""
with open(stage_path, 'w', encoding='utf-8') as f:
    f.write(stage_content)

print("Fixes applied.")
