import GrodCore
import SwiftUI

/// grod UI (ui/README.md) in SwiftUI: warm neutrals, color only for state, no shadows, color-only motion.
struct SettingsView: View {
    @Bindable var model: SettingsModel
    @State private var channelInput = ""
    @State private var allowKind = AllowKind.package
    @State private var allowInput = ""
    @State private var newTime = "12:00"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                phoneStatus
                rulesSection
                notificationsSection
                channelsSection
                reviewSection
                safariSection
            }
            .padding(24)
        }
        .font(.system(size: 13))
        .foregroundStyle(GrodColor.foreground)
        .background(GrodColor.background)
        .tint(GrodColor.accent)
    }

    // MARK: Sections

    private var phoneStatus: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Image(systemName: "iphone").foregroundStyle(GrodColor.mutedForeground)
            RowText(model.phone.title, meta: model.phone.detail)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(GrodColor.muted, in: RoundedRectangle(cornerRadius: 6))
        .accessibilityElement(children: .combine)
    }

    private var rulesSection: some View {
        GrodSection("Rules", error: model.errors[.rules], note: model.notes[.rules]) {
            Rows(model.rules) { rule in
                Row {
                    Text(rule.title).foregroundStyle(rule.enabled ? GrodColor.foreground : GrodColor.mutedForeground)
                } end: {
                    GrodSwitch(rule.title, isOn: Binding(get: { rule.enabled }, set: { model.setRule(rule.id, enabled: $0) }))
                }
            }
        }
    }

    private var notificationsSection: some View {
        GrodSection("Notifications on the phone", trailing: model.phone.short, error: model.errors[.notifications], note: model.notes[.notifications]) {
            Row {
                RowText("Hold notifications", meta: "Delivered together. Calls and alarms always ring.")
            } end: {
                GrodSwitch("Hold notifications", isOn: Binding(get: { model.notificationsEnabled }, set: { model.setNotifications($0) }))
            }
            RowDivider()
            Row {
                RowText("Deliver")
            } end: {
                Choice(["Every N minutes", "At fixed times"], selected: model.deliverAt == nil ? 0 : 1) { model.setFixedTimes($0 == 1) }
            }
            RowDivider()
            if let times = model.deliverAt {
                ForEach(times, id: \.self) { time in
                    Row {
                        Text(time).monospacedDigit()
                    } end: {
                        GrodButton("Remove", style: .ghost) { model.removeTime(time) }
                            .disabled(times.count == 1)
                            .help(times.count == 1 ? "Keep at least one time, or deliver every N minutes." : "")
                    }
                    RowDivider()
                }
                Row {
                    TimePicker(time: $newTime)
                } end: {
                    GrodButton("Add time") { model.addTime(newTime) }.disabled(times.contains(newTime))
                }
            } else {
                Row {
                    RowText("Every")
                } end: {
                    MenuPicker(selection: Binding(get: { model.deliverEvery }, set: { model.setDeliverEvery($0) }),
                               options: Self.everyOptions(including: model.deliverEvery), label: Self.minutesLabel)
                }
            }
            RowDivider()
            Row {
                RowText("Keep history", meta: "Days the phone keeps every notification it saw.")
            } end: {
                MenuPicker(selection: Binding(get: { model.keepDays }, set: { model.setKeepDays($0) }),
                           options: Self.keepOptions(including: model.keepDays), label: { $0 == 0 ? "None" : $0 == 1 ? "1 day" : "\($0) days" })
            }
            RowDivider()
            Row {
                RowText("Arrive right away", meta: "A notification that matches every field of an entry is never held.")
            } end: { EmptyView() }
            ForEach(model.allow, id: \.self) { entry in
                RowDivider()
                Row {
                    Text(Self.describe(entry))
                } end: {
                    GrodButton("Remove", style: .ghost) { model.removeAllow(entry) }
                        .disabled(entry.cliArgument == nil)
                        .help(entry.cliArgument == nil ? "Combined entries are edited with the grod CLI." : "")
                }
            }
            RowDivider()
            Row {
                HStack(spacing: 8) {
                    MenuPicker(selection: $allowKind, options: AllowKind.allCases, label: \.title)
                    GrodTextField(allowKind.placeholder, text: $allowInput, onSubmit: addAllow)
                }
            } end: {
                GrodButton("Allow", action: addAllow).disabled(allowEntry == nil)
            }
        }
    }

    private var channelsSection: some View {
        GrodSection("Blocked YouTube channels", error: model.errors[.channels], note: model.notes[.channels]) {
            if model.channels.isEmpty {
                Row { Text("No channels blocked.").foregroundStyle(GrodColor.mutedForeground) } end: { EmptyView() }
                RowDivider()
            }
            ForEach(model.channels, id: \.handle) { channel in
                Row {
                    RowText(channel.name, meta: channel.handle)
                } end: {
                    GrodButton("Remove", style: .ghost) { model.removeChannel(channel) }
                }
                RowDivider()
            }
            Row {
                GrodTextField("Channel or video link, or @handle", text: $channelInput, onSubmit: addChannel)
                    .disabled(model.addingChannel)
            } end: {
                GrodButton(model.addingChannel ? "Blocking…" : "Block", action: addChannel)
                    .disabled(model.addingChannel || channelInput.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private var reviewSection: some View {
        GrodSection("Weekly review", error: model.errors[.review], note: model.notes[.review]) {
            Row {
                RowText("Reminder on the phone", meta: model.lastReview.map {
                    "Last review: \($0.formatted(date: .abbreviated, time: .omitted))"
                } ?? "No review yet.")
            } end: {
                HStack(spacing: 8) {
                    MenuPicker(selection: Binding(get: { model.reviewWeekday }, set: { model.setReview(weekday: $0, time: model.reviewTime) }),
                               options: Array(1...7), label: Self.weekdayName)
                    TimePicker(time: Binding(get: { model.reviewTime }, set: { model.setReview(weekday: model.reviewWeekday, time: $0) }))
                }
            }
        }
    }

    private var safariSection: some View {
        GrodSection("Safari") {
            Row {
                RowText("Safari guard", meta: "Applies the web rules to Safari's front tab.")
            } end: {
                GrodSwitch("Safari guard", isOn: $model.safariGuard)
            }
        }
    }

    // MARK: Actions

    private var allowEntry: NotificationAllow? {
        let value = allowInput.trimmingCharacters(in: .whitespaces)
        guard !value.isEmpty else { return nil }
        switch allowKind {
        case .package: return NotificationAllow(package: value)
        case .category: return NotificationAllow(category: value)
        case .title: return NotificationAllow(titleContains: value)
        }
    }

    private func addAllow() {
        guard let entry = allowEntry else { return }
        model.addAllow(entry)
        allowInput = ""
    }

    private func addChannel() {
        model.addChannel(channelInput) { channelInput = "" }
    }

    // MARK: Labels

    enum AllowKind: CaseIterable, Hashable {
        case package, category, title

        var title: String {
            switch self {
            case .package: "App package"
            case .category: "Category"
            case .title: "Title contains"
            }
        }

        var placeholder: String {
            switch self {
            case .package: "com.whatsapp"
            case .category: "reminder"
            case .title: "Contact name"
            }
        }
    }

    private static func describe(_ entry: NotificationAllow) -> String {
        [entry.package.map { "App \($0)" }, entry.category.map { "Category \($0)" }, entry.titleContains.map { "Title contains “\($0)”" }]
            .compactMap { $0 }.joined(separator: " · ")
    }

    private static func everyOptions(including current: Int) -> [Int] {
        Set([15, 30, 60, 90, 120, 180, 240, 360, 480, 720, current]).sorted()
    }

    private static func keepOptions(including current: Int) -> [Int] {
        Set([0, 1, 7, 14, 30, 60, 90, 180, 365, current]).sorted()
    }

    private static func minutesLabel(_ minutes: Int) -> String {
        switch minutes {
        case 60: "hour"
        case let m where m % 60 == 0: "\(m / 60) hours"
        default: "\(minutes) minutes"
        }
    }

    /// ISO weekday, 1 = Monday.
    private static func weekdayName(_ weekday: Int) -> String {
        Calendar.current.weekdaySymbols[weekday % 7]
    }
}

// MARK: - grod UI components

private struct GrodSection<Content: View>: View {
    let title: String
    let trailing: String?
    let error: String?
    let note: String?
    @ViewBuilder let content: Content

    init(_ title: String, trailing: String? = nil, error: String? = nil, note: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.trailing = trailing
        self.error = error
        self.note = note
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(title.uppercased())
                    .font(.system(size: 12, weight: .semibold))
                    .kerning(0.7)
                if let trailing {
                    Spacer()
                    Text(trailing).font(.system(size: 12))
                }
            }
            .foregroundStyle(GrodColor.mutedForeground)
            .padding(.horizontal, 13)
            VStack(alignment: .leading, spacing: 0) { content }
                .background(GrodColor.surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(GrodColor.border))
            if let note, error == nil {
                Text(note)
                    .font(.system(size: 12))
                    .foregroundStyle(GrodColor.mutedForeground)
                    .padding(.horizontal, 13)
                    .textSelection(.enabled)
            }
            if let error {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Not saved").fontWeight(.semibold).foregroundStyle(GrodColor.caution)
                    Text(error).textSelection(.enabled)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(GrodColor.surface, in: RoundedRectangle(cornerRadius: 6))
                .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(GrodColor.caution))
            }
        }
    }
}

/// Rows with a 1px border line between them.
private struct Rows<Data: RandomAccessCollection, RowContent: View>: View where Data.Element: Identifiable {
    let data: Data
    @ViewBuilder let row: (Data.Element) -> RowContent

    init(_ data: Data, @ViewBuilder row: @escaping (Data.Element) -> RowContent) {
        self.data = data
        self.row = row
    }

    var body: some View {
        ForEach(Array(data.enumerated()), id: \.element.id) { index, element in
            if index > 0 { RowDivider() }
            row(element)
        }
    }
}

private struct RowDivider: View {
    var body: some View { Rectangle().fill(GrodColor.border).frame(height: 1) }
}

private struct Row<Main: View, End: View>: View {
    @ViewBuilder let main: Main
    @ViewBuilder let end: End

    var body: some View {
        HStack(spacing: 12) {
            main.frame(maxWidth: .infinity, alignment: .leading)
            end
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(minHeight: 40)
    }
}

private struct RowText: View {
    let title: String
    let meta: String?

    init(_ title: String, meta: String? = nil) {
        self.title = title
        self.meta = meta
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            if let meta { Text(meta).font(.system(size: 12)).foregroundStyle(GrodColor.mutedForeground) }
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// g-switch: the thumb position shows the state too, and it jumps; only color animates.
private struct GrodSwitch: View {
    let label: String
    @Binding var isOn: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(_ label: String, isOn: Binding<Bool>) {
        self.label = label
        _isOn = isOn
    }

    var body: some View {
        Button { isOn.toggle() } label: {
            ZStack(alignment: isOn ? .trailing : .leading) {
                RoundedRectangle(cornerRadius: 6)
                    .fill(isOn ? GrodColor.accent : GrodColor.surface)
                    .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(isOn ? GrodColor.accent : GrodColor.input))
                RoundedRectangle(cornerRadius: 4)
                    .fill(isOn ? GrodColor.accentForeground : GrodColor.input)
                    .frame(width: 14, height: 14)
                    .padding(3)
                    .transaction { $0.animation = nil }
            }
            .frame(width: 34, height: 20)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: isOn)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityValue(isOn ? "On" : "Off")
        .accessibilityAddTraits(.isToggle)
    }
}

private struct GrodButton: View {
    enum Style { case quiet, ghost }

    let title: String
    let style: Style
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    init(_ title: String, style: Style = .quiet, action: @escaping () -> Void) {
        self.title = title
        self.style = style
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .fontWeight(.semibold)
                .foregroundStyle(isEnabled ? (style == .ghost ? GrodColor.mutedForeground : GrodColor.foreground) : GrodColor.mutedForeground)
                .padding(.horizontal, 12)
                .padding(.vertical, 4)
                .background(style == .quiet && isEnabled ? GrodColor.surface : Color.clear, in: RoundedRectangle(cornerRadius: 6))
                .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(style == .quiet && isEnabled ? GrodColor.input : Color.clear))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Two equal-weight options; the chosen one is marked by fill and weight, never by an accent.
private struct Choice: View {
    let options: [String]
    let selected: Int
    let choose: (Int) -> Void

    init(_ options: [String], selected: Int, choose: @escaping (Int) -> Void) {
        self.options = options
        self.selected = selected
        self.choose = choose
    }

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options.indices, id: \.self) { index in
                Button { choose(index) } label: {
                    Text(options[index])
                        .lineLimit(1)
                        .fontWeight(index == selected ? .semibold : .regular)
                        .foregroundStyle(index == selected ? GrodColor.foreground : GrodColor.mutedForeground)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 3)
                        .background(index == selected ? GrodColor.muted : Color.clear, in: RoundedRectangle(cornerRadius: 4))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(index == selected ? .isSelected : [])
            }
        }
        .padding(2)
        .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(GrodColor.input))
        .fixedSize()
    }
}

private struct GrodTextField: View {
    let placeholder: String
    @Binding var text: String
    let onSubmit: () -> Void

    init(_ placeholder: String, text: Binding<String>, onSubmit: @escaping () -> Void) {
        self.placeholder = placeholder
        _text = text
        self.onSubmit = onSubmit
    }

    var body: some View {
        TextField(placeholder, text: $text)
            .textFieldStyle(.plain)
            .onSubmit(onSubmit)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(GrodColor.surface, in: RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(GrodColor.input))
    }
}

/// A menu drawn like a g-input, so it reads the same in light and dark and in an inactive window.
private struct MenuPicker<Value: Hashable>: View {
    @Binding var selection: Value
    let options: [Value]
    let label: (Value) -> String

    var body: some View {
        Menu {
            Picker("", selection: $selection) {
                ForEach(options, id: \.self) { Text(label($0)).tag($0) }
            }
            .pickerStyle(.inline)
            .labelsHidden()
        } label: {
            HStack(spacing: 6) {
                Text(label(selection)).foregroundStyle(GrodColor.foreground)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(GrodColor.mutedForeground)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(GrodColor.surface, in: RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(GrodColor.input))
            .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        .fixedSize()
        .accessibilityValue(label(selection))
    }
}

/// HH:MM from an hour and a five-minute menu; a time written elsewhere keeps its own minute.
private struct TimePicker: View {
    @Binding var time: String

    var body: some View {
        let (hour, minute) = ClockTime.parse(time) ?? (12, 0)
        HStack(spacing: 4) {
            MenuPicker(selection: Binding(get: { hour }, set: { time = ClockTime.format(hour: $0, minute: minute) }),
                       options: Array(0...23), label: { String(format: "%02d", $0) })
            Text(":").foregroundStyle(GrodColor.mutedForeground)
            MenuPicker(selection: Binding(get: { minute }, set: { time = ClockTime.format(hour: hour, minute: $0) }),
                       options: Set(stride(from: 0, to: 60, by: 5)).union([minute]).sorted(), label: { String(format: "%02d", $0) })
        }
        .monospacedDigit()
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Time")
    }
}
