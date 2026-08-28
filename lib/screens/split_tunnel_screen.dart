import 'package:flutter/material.dart';
import 'package:installed_apps/app_info.dart';
import '../config/app_theme.dart';
import '../services/split_tunnel_service.dart';

class SplitTunnelScreen extends StatefulWidget {
  const SplitTunnelScreen({super.key});

  @override
  State<SplitTunnelScreen> createState() => _SplitTunnelScreenState();
}

class _SplitTunnelScreenState extends State<SplitTunnelScreen> {
  bool _isEnabled = false;
  SplitTunnelMode _mode = SplitTunnelMode.selectedOnly;
  List<AppInfo> _allApps = [];
  Set<String> _selectedApps = {};
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);

    final enabled = await SplitTunnelService.isEnabled();
    final mode = await SplitTunnelService.getMode();
    final selectedApps = await SplitTunnelService.getSelectedApps();
    final allApps = await SplitTunnelService.getInstalledApps();

    setState(() {
      _isEnabled = enabled;
      _mode = mode;
      _allApps = allApps;
      _selectedApps = selectedApps.toSet();
      _isLoading = false;
    });
  }

  Future<void> _onEnabledChanged(bool value) async {
    setState(() => _isEnabled = value);
    await SplitTunnelService.setEnabled(value);
  }

  Future<void> _onModeChanged(SplitTunnelMode mode) async {
    setState(() => _mode = mode);
    await SplitTunnelService.setMode(mode);
  }

  Future<void> _toggleApp(String packageName) async {
    setState(() {
      if (_selectedApps.contains(packageName)) {
        _selectedApps.remove(packageName);
      } else {
        _selectedApps.add(packageName);
      }
    });
    await SplitTunnelService.setSelectedApps(_selectedApps.toList());
  }

  Future<void> _selectAll() async {
    setState(() {
      _selectedApps = _allApps.map((a) => a.packageName).toSet();
    });
    await SplitTunnelService.setSelectedApps(_selectedApps.toList());
  }

  Future<void> _deselectAll() async {
    setState(() {
      _selectedApps.clear();
    });
    await SplitTunnelService.setSelectedApps([]);
  }

  void _showAppSelector() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => _AppSelectorSheet(
        allApps: _allApps,
        selectedApps: _selectedApps,
        onToggle: _toggleApp,
        onSelectAll: _selectAll,
        onDeselectAll: _deselectAll,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBackground,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios, color: Colors.white),
          onPressed: () => Navigator.pop(context),
        ),
        title: const Text(
          'Split Tunneling',
          style: TextStyle(color: Colors.white),
        ),
      ),
      body: _isLoading
          ? const Center(
              child: CircularProgressIndicator(color: AppColors.primaryGreen),
            )
          : SingleChildScrollView(
              padding: const EdgeInsets.only(bottom: 32),
              child: Column(
                children: [
                  _buildEnableSwitch(),
                  if (_isEnabled) ...[
                    const SizedBox(height: 4),
                    _buildModeSelector(),
                    const SizedBox(height: 8),
                    _buildModeDescription(),
                    const SizedBox(height: 12),
                    _buildAppSelectorButton(),
                  ],
                ],
              ),
            ),
    );
  }

  Widget _buildEnableSwitch() {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.cardBackground,
        borderRadius: BorderRadius.circular(16),
        border: _isEnabled
            ? Border.all(
                color: AppColors.primaryGreen.withOpacity(0.3), width: 1)
            : null,
      ),
      child: SwitchListTile(
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
        title: const Text(
          'Split Tunneling',
          style: TextStyle(
            color: Colors.white,
            fontSize: 16,
            fontWeight: FontWeight.w600,
          ),
        ),
        subtitle: Text(
          _isEnabled
              ? 'Выберите приложения для VPN'
              : 'VPN работает для всех приложений',
          style: TextStyle(
            color: Colors.white.withOpacity(0.5),
            fontSize: 13,
          ),
        ),
        value: _isEnabled,
        onChanged: _onEnabledChanged,
        activeColor: AppColors.primaryGreen,
        activeTrackColor: AppColors.primaryGreen.withOpacity(0.3),
        inactiveThumbColor: Colors.white38,
        inactiveTrackColor: Colors.white12,
      ),
    );
  }

  Widget _buildModeSelector() {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: AppColors.cardBackground,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          _buildModeOption(
            SplitTunnelMode.selectedOnly,
            'Только выбранные',
            'VPN работает ТОЛЬКО для отмеченных приложений',
            Icons.check_circle_outline,
          ),
          Divider(color: Colors.white.withOpacity(0.05), height: 1),
          _buildModeOption(
            SplitTunnelMode.excludeSelected,
            'Все кроме выбранных',
            'VPN работает для ВСЕХ, кроме отмеченных',
            Icons.remove_circle_outline,
          ),
        ],
      ),
    );
  }

  Widget _buildModeOption(
    SplitTunnelMode mode,
    String title,
    String subtitle,
    IconData icon,
  ) {
    final isSelected = _mode == mode;
    return InkWell(
      onTap: () => _onModeChanged(mode),
      borderRadius: BorderRadius.circular(16),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          children: [
            Icon(
              icon,
              color:
                  isSelected ? AppColors.primaryGreen : Colors.white38,
              size: 22,
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      color: isSelected ? Colors.white : Colors.white70,
                      fontSize: 15,
                      fontWeight: isSelected
                          ? FontWeight.w600
                          : FontWeight.normal,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: TextStyle(
                      color: Colors.white.withOpacity(0.4),
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
            Radio<SplitTunnelMode>(
              value: mode,
              groupValue: _mode,
              onChanged: (v) => _onModeChanged(v!),
              activeColor: AppColors.primaryGreen,
              fillColor: WidgetStateProperty.resolveWith((states) {
                if (states.contains(WidgetState.selected)) {
                  return AppColors.primaryGreen;
                }
                return Colors.white38;
              }),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildModeDescription() {
    final isSelectedOnly = _mode == SplitTunnelMode.selectedOnly;
    final text = isSelectedOnly
        ? 'Отметьте приложения, которые будут работать через VPN'
        : 'Отметьте приложения, которые будут работать БЕЗ VPN';
    final icon =
        isSelectedOnly ? Icons.vpn_lock : Icons.public_off;
    final color =
        isSelectedOnly ? AppColors.primaryGreen : AppColors.warning;

    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: color.withOpacity(0.08),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withOpacity(0.2)),
      ),
      child: Row(
        children: [
          Icon(icon, color: color, size: 20),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              text,
              style: TextStyle(color: color, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAppSelectorButton() {
    final count = _selectedApps.length;
    final hasSelection = count > 0;

    return GestureDetector(
      onTap: _showAppSelector,
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 16),
        padding:
            const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        decoration: BoxDecoration(
          color: AppColors.cardBackground,
          borderRadius: BorderRadius.circular(16),
          border: hasSelection
              ? Border.all(
                  color: AppColors.primaryGreen.withOpacity(0.3),
                  width: 1)
              : Border.all(
                  color: Colors.white.withOpacity(0.06), width: 1),
        ),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: hasSelection
                    ? AppColors.primaryGreen.withOpacity(0.12)
                    : Colors.white.withOpacity(0.06),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(
                Icons.apps_rounded,
                color: hasSelection
                    ? AppColors.primaryGreen
                    : Colors.white38,
                size: 22,
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Выбрать приложения',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 15,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    hasSelection
                        ? '$count ${_pluralApps(count)}'
                        : 'Нет выбранных приложений',
                    style: TextStyle(
                      color: hasSelection
                          ? AppColors.primaryGreen
                          : Colors.white.withOpacity(0.35),
                      fontSize: 13,
                    ),
                  ),
                ],
              ),
            ),
            Icon(
              Icons.chevron_right_rounded,
              color: Colors.white.withOpacity(0.3),
              size: 22,
            ),
          ],
        ),
      ),
    );
  }

  String _pluralApps(int count) {
    if (count % 100 >= 11 && count % 100 <= 19) return 'приложений';
    switch (count % 10) {
      case 1:
        return 'приложение';
      case 2:
      case 3:
      case 4:
        return 'приложения';
      default:
        return 'приложений';
    }
  }
}

// ─────────────────────────────────────────────────────────────
// Модальное окно выбора приложений
// ─────────────────────────────────────────────────────────────

class _AppSelectorSheet extends StatefulWidget {
  const _AppSelectorSheet({
    required this.allApps,
    required this.selectedApps,
    required this.onToggle,
    required this.onSelectAll,
    required this.onDeselectAll,
  });

  final List<AppInfo> allApps;
  final Set<String> selectedApps;
  final Future<void> Function(String) onToggle;
  final Future<void> Function() onSelectAll;
  final Future<void> Function() onDeselectAll;

  @override
  State<_AppSelectorSheet> createState() => _AppSelectorSheetState();
}

class _AppSelectorSheetState extends State<_AppSelectorSheet> {
  String _searchQuery = '';
  final TextEditingController _searchController = TextEditingController();

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<AppInfo> get _filteredApps {
    if (_searchQuery.isEmpty) return widget.allApps;
    final q = _searchQuery.toLowerCase();
    return widget.allApps.where((app) {
      return app.name.toLowerCase().contains(q) ||
          app.packageName.toLowerCase().contains(q);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    return DraggableScrollableSheet(
      initialChildSize: 0.75,
      minChildSize: 0.5,
      maxChildSize: 0.95,
      snap: true,
      snapSizes: const [0.75, 0.95],
      builder: (context, scrollController) {
        return Container(
          decoration: const BoxDecoration(
            color: AppColors.cardBackground,
            borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
          ),
          child: Column(
            children: [
              // Drag handle
              _buildHandle(),
              // Шапка
              _buildHeader(context),
              // Поиск
              _buildSearchBar(),
              // Счётчик + кнопки
              _buildCounterRow(),
              // Разделитель
              Divider(
                  color: Colors.white.withOpacity(0.06),
                  height: 1,
                  thickness: 1),
              // Список
              Expanded(child: _buildAppList(scrollController)),
            ],
          ),
        );
      },
    );
  }

  Widget _buildHandle() {
    return Padding(
      padding: const EdgeInsets.only(top: 12, bottom: 4),
      child: Container(
        width: 40,
        height: 4,
        decoration: BoxDecoration(
          color: Colors.white.withOpacity(0.15),
          borderRadius: BorderRadius.circular(2),
        ),
      ),
    );
  }

  Widget _buildHeader(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 8, 8),
      child: Row(
        children: [
          const Text(
            'Приложения',
            style: TextStyle(
              color: Colors.white,
              fontSize: 18,
              fontWeight: FontWeight.w700,
            ),
          ),
          const Spacer(),
          TextButton(
            onPressed: () => Navigator.pop(context),
            style: TextButton.styleFrom(
              foregroundColor: AppColors.primaryGreen,
              padding:
                  const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            ),
            child: const Text(
              'Готово',
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSearchBar() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
      child: TextField(
        controller: _searchController,
        onChanged: (v) => setState(() => _searchQuery = v),
        style: const TextStyle(color: Colors.white, fontSize: 14),
        decoration: InputDecoration(
          hintText: 'Поиск приложений...',
          hintStyle:
              TextStyle(color: Colors.white.withOpacity(0.3), fontSize: 14),
          prefixIcon:
              Icon(Icons.search, color: Colors.white.withOpacity(0.3), size: 20),
          suffixIcon: _searchQuery.isNotEmpty
              ? IconButton(
                  icon: Icon(Icons.clear,
                      color: Colors.white.withOpacity(0.3), size: 18),
                  onPressed: () {
                    _searchController.clear();
                    setState(() => _searchQuery = '');
                  },
                )
              : null,
          filled: true,
          fillColor: Colors.white.withOpacity(0.06),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: BorderSide.none,
          ),
          contentPadding:
              const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        ),
      ),
    );
  }

  Widget _buildCounterRow() {
    final filteredCount = _filteredApps.length;
    final selectedCount = widget.selectedApps.length;

    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 0, 16, 10),
      child: Row(
        children: [
          Text(
            'Всего: $filteredCount',
            style: TextStyle(
              color: Colors.white.withOpacity(0.4),
              fontSize: 13,
            ),
          ),
          const SizedBox(width: 12),
          if (selectedCount > 0)
            Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: AppColors.primaryGreen.withOpacity(0.15),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                'Выбрано: $selectedCount',
                style: const TextStyle(
                  color: AppColors.primaryGreen,
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
          const Spacer(),
          TextButton(
            onPressed: () async {
              await widget.onSelectAll();
              setState(() {});
            },
            style: TextButton.styleFrom(
              foregroundColor: Colors.white54,
              padding:
                  const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              minimumSize: Size.zero,
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            child: const Text('Все', style: TextStyle(fontSize: 13)),
          ),
          Text('·',
              style: TextStyle(
                  color: Colors.white.withOpacity(0.2), fontSize: 13)),
          TextButton(
            onPressed: () async {
              await widget.onDeselectAll();
              setState(() {});
            },
            style: TextButton.styleFrom(
              foregroundColor: Colors.white54,
              padding:
                  const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              minimumSize: Size.zero,
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            ),
            child: const Text('Снять', style: TextStyle(fontSize: 13)),
          ),
        ],
      ),
    );
  }

  Widget _buildAppList(ScrollController scrollController) {
    final apps = _filteredApps;

    if (apps.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.search_off,
                color: Colors.white.withOpacity(0.15), size: 48),
            const SizedBox(height: 12),
            Text(
              'Приложения не найдены',
              style:
                  TextStyle(color: Colors.white.withOpacity(0.3), fontSize: 14),
            ),
          ],
        ),
      );
    }

    return ListView.builder(
      controller: scrollController,
      padding: const EdgeInsets.fromLTRB(12, 4, 12, 24),
      itemCount: apps.length,
      itemBuilder: (context, index) => _buildAppTile(apps[index]),
    );
  }

  Widget _buildAppTile(AppInfo app) {
    final isSelected = widget.selectedApps.contains(app.packageName);

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () async {
          await widget.onToggle(app.packageName);
          setState(() {});
        },
        borderRadius: BorderRadius.circular(12),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          margin: const EdgeInsets.symmetric(vertical: 2),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: isSelected
                ? AppColors.primaryGreen.withOpacity(0.07)
                : Colors.transparent,
            borderRadius: BorderRadius.circular(12),
            border: isSelected
                ? Border.all(
                    color: AppColors.primaryGreen.withOpacity(0.15), width: 1)
                : null,
          ),
          child: Row(
            children: [
              // Иконка приложения
              ClipRRect(
                borderRadius: BorderRadius.circular(10),
                child: app.icon != null
                    ? Image.memory(
                        app.icon!,
                        width: 42,
                        height: 42,
                        fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) => _buildDefaultIcon(),
                      )
                    : _buildDefaultIcon(),
              ),
              const SizedBox(width: 14),
              // Название и package
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      app.name,
                      style: TextStyle(
                        color: isSelected
                            ? Colors.white
                            : Colors.white.withOpacity(0.85),
                        fontSize: 14,
                        fontWeight: isSelected
                            ? FontWeight.w600
                            : FontWeight.normal,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 2),
                    Text(
                      app.packageName,
                      style: TextStyle(
                        color: Colors.white.withOpacity(0.25),
                        fontSize: 11,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              // Чекбокс
              AnimatedContainer(
                duration: const Duration(milliseconds: 150),
                width: 22,
                height: 22,
                decoration: BoxDecoration(
                  color: isSelected
                      ? AppColors.primaryGreen
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(6),
                  border: isSelected
                      ? null
                      : Border.all(
                          color: Colors.white.withOpacity(0.25), width: 1.5),
                ),
                child: isSelected
                    ? const Icon(Icons.check_rounded,
                        color: Colors.black, size: 14)
                    : null,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildDefaultIcon() {
    return Container(
      width: 42,
      height: 42,
      decoration: BoxDecoration(
        color: AppColors.surfaceColor,
        borderRadius: BorderRadius.circular(10),
      ),
      child: const Icon(Icons.android, color: Colors.white38, size: 24),
    );
  }
}
