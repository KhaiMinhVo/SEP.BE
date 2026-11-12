# Class Diagrams

Class diagram tổng quan và 5 bản chi tiết.

- [Mở bộ xem](index.html)
- [Hướng dẫn và đối chiếu tài liệu](../README.md)

Mã PlantUML nằm trực tiếp trong thư mục này. PNG/SVG nằm trong `rendered/`.

Từ gốc repository, xuất lại hình bằng:

```powershell
& docs/diagrams/render.ps1 -PlantUmlJar target/uml-tools/plantuml.jar -JavaExecutable "C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot\bin\java.exe" -DiagramDirectory docs/diagrams/class-diagram
```
