---
description: Navigate and debug the medical company's ExtJS + Spring MVC full-stack codebase (SPD, Logistics, Assets, Humanrs projects)
---

# ExtJS / Spring MVC Full-Stack Code Navigation

Navigate, locate, and debug features in the medical company's legacy enterprise systems built with ExtJS/Bootstrap Table frontend + Spring MVC + Hibernate/MyBatis + SQL Server.

## Applies To

- `F:\医疗公司\SPD` — Spring Boot + Thymeleaf + MyBatis + SQL Server
- `F:\医疗公司\public2\Logistics` — Struts2 + Hibernate + ExtJS + SQL Server
- `F:\医疗公司\public2\Assets` — ExtJS + JSP + Spring MVC
- `F:\医疗公司\Humanrs` — Spring MVC + Thymeleaf

## Architecture Pattern

All four projects follow the same layered pattern:

```
Frontend (ExtJS / Bootstrap Table / JSP / Thymeleaf HTML)
  ↕ AJAX/JSON
Controller (Java, Spring MVC @Controller or Struts2 Action)
  ↕ method calls
Service (Interface + Impl)
  ↕ ORM calls
DAO / Mapper (Hibernate HBM XML or MyBatis Mapper XML)
  ↕ SQL
SQL Server Database
```

## Navigation Procedure

When asked "where is feature X?" or "fix bug in X":

### Step 1: Identify the feature keyword
Extract the Chinese or English keyword from the user's description (e.g., "采购订单" → purchase order, "物资属性" → material attribute, "订单接收" → order reception).

### Step 2: Search across layers
```bash
# Search for the keyword across the entire project
grep -r "keyword" project_dir/src/ --include="*.java" --include="*.xml" --include="*.js" --include="*.html" --include="*.jsp"
```

Prioritize search order:
1. **Controller/Action** — URL mappings, request handlers
2. **Service** — business logic
3. **DAO/Mapper XML** — SQL queries
4. **Frontend JS** — ExtJS grid definitions, event handlers
5. **Frontend HTML/JSP** — page templates

### Step 3: Read the controller/action
Identify the entry point: URL pattern, HTTP method, parameters, and which service method it calls.

### Step 4: Trace to service → DAO → SQL
Follow the call chain. Key files to check:
- **SPD**: `src/main/java/com/sys/web/project/...Controller.java` → `service/...ServiceImpl.java` → `mapper/...Mapper.java` → `src/main/resources/mapper/logistics/...Mapper.xml`
- **Logistics**: `src/com/rphrp/project/.../action/...Action.java` → `service/...ServiceImpl.java` → `dao/...DAO.java`
- **Assets**: `WebRoot/project/.../...js` (ExtJS) → Java action/service

### Step 5: Find the frontend code
- **SPD**: `src/main/resources/templates/project/.../view.html` or `edit.html` (Thymeleaf)
- **Logistics**: `WebRoot/project/.../...js` (ExtJS grid/panel definitions)
- **Assets**: `WebRoot/project/.../...js` + `...jsp`

### Step 6: Check for src/target synchronization (SPD only)
SPD uses Spring Boot with static resources. After editing files in `src/main/resources/static/`, check if a `target/classes/static/` copy exists and update it too:
```bash
# Check if target has a copy
ls target/classes/static/ajax/libs/.../same-file.js
```
If it exists, apply the same edit to both locations. Some sessions also do manual copy:
```powershell
copy "src\main\resources\static\ajax\libs\...\file.js" "target\classes\static\ajax\libs\...\file.js"
```

## Common Debug Patterns

### "数据显示数字而非中文"
- Enum fields stored as numeric codes in DB
- Frontend needs `valueLabelMap` or `getValueLabel()` for code→label translation
- Backend `attrMap.getOrDefault()` may return null if value is null
- Check both frontend JS and backend Service for translation logic

### "下拉框无数据"
- Check the AJAX URL in the combobox config
- Trace to the controller endpoint
- Check the SQL query in mapper XML for correct table/JOIN
- Verify the data actually exists in the database

### "状态回显错误"
- Check the EL utility class (`ELUtils.java` in SPD) or the Thymeleaf fragment (`fragment/field.html`)
- State values must match between: DB values, Java enum/label mapping, frontend CSS classes (`.state-color-N`)
- SPD uses `field_4_readonly2` fragment with hardcoded `th:if` conditions

### "保存失败"
- Check for null values in required fields
- Check `@Transactional` propagation
- Check unique constraints (DuplicateKeyException)
- Check for前端 `_saveEditData` race conditions setting null

## Project-Specific Notes

### SPD
- Uses dual data source: `master` (SPD DB) + `logistics` (Logistics DB) via `@DataSource` annotation
- JDK 8 (`.idea/misc.xml` confirms `languageLevel="JDK_1_8"`)
- SVN version control (not git)
- Port 82
- `new-material-editor2.js` has `editorCache` singleton — select-type editors must bypass cache
- PageHelper on SQL Server transforms ORDER BY column aliases (use lowercase+underscore format like `sort_state`)

### Logistics
- Struts2 actions, Hibernate HBM XML mappings
- `BaseAction.outJson()` for JSON responses
- `WebRoot/project/` contains all frontend JS organized by module

### Assets
- ExtJS 3.2.0 — `EditorGridPanel` with ComboBox column editors
- `openFullScreen: true` for modal windows (defined in `WebRoot/js/jslib/openFullScreen.js`)
- External CSS files are unreliable due to browser caching — use inline styles in JS
- `tabPosition:"left"` broken in ExtJS 3.2.0 — use CardLayout + custom HTML sidebar instead

### Humanrs
- Spring MVC + Thymeleaf templates
- Personnel records with party branch (党组织) fields
