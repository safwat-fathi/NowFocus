import SwiftUI
import AppKit
import NowFocusCore

/// Flat 280px list + rule + detail pane, modeled on the design's Profiles
/// screen — `NavigationSplitView`/`.listStyle(.sidebar)` draw native rounded/
/// translucent chrome that can't be restyled to match, so this is a plain
/// `HStack` instead.
struct PolicyListView: View {
    @State private var policies: [BlockPolicy] = []
    @State private var selectedPolicyId: String?
    @State private var showingSessionBlockAlert = false

    var body: some View {
        HStack(spacing: 0) {
            list
            NowFocusRule(vertical: true)
            detail
                .frame(minWidth: 360, maxWidth: .infinity, maxHeight: .infinity)
        }
        .onAppear { loadPolicies() }
        .onReceive(NotificationCenter.default.publisher(for: .nowFocusSyncApplied)) { _ in loadPolicies() }
    }

    private var list: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Profiles")
                    .font(NowFocusFonts.heading(20))
                    .foregroundColor(NowFocusColors.ink)
                Spacer()
                Button(action: addPolicy) {
                    Image(systemName: "plus")
                        .foregroundColor(NowFocusColors.ink)
                }
                .buttonStyle(.plain)
            }
            .padding(NowFocusSpace.s4)

            NowFocusRule(thick: true)

            ScrollView {
                VStack(spacing: 0) {
                    ForEach(policies) { policy in
                        listRow(policy)
                    }
                }
            }

            Spacer(minLength: 0)
            NowFocusRule()
        }
        .frame(width: 280)
    }

    private func listRow(_ policy: BlockPolicy) -> some View {
        let isSelected = selectedPolicyId == policy.id
        return PolicyListRowView(
            policy: policy,
            isSelected: isSelected,
            onSelect: { selectedPolicyId = policy.id },
            onDelete: { delete(policy) }
        )
    }

    private struct PolicyListRowView: View {
        let policy: BlockPolicy
        let isSelected: Bool
        let onSelect: () -> Void
        let onDelete: () -> Void
        @State private var isHovering = false

        var body: some View {
            Button(action: onSelect) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(policy.name)
                            .font(NowFocusFonts.body(14).weight(.semibold))
                            .foregroundColor(NowFocusColors.ink)
                        Text(verbatim: sitesAppsSummary(sites: policy.domains.count, apps: policy.applications.count))
                            .font(NowFocusFonts.body(12))
                            .foregroundColor(NowFocusColors.neutral700)
                    }
                    Spacer()
                    if isHovering {
                        Button(action: onDelete) {
                            Image(systemName: "trash")
                                .foregroundColor(NowFocusColors.accent700)
                        }
                        .buttonStyle(.plain)
                        .padding(.trailing, 4)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, NowFocusSpace.s4)
                .padding(.trailing, NowFocusSpace.s3)
                .padding(.vertical, NowFocusSpace.s2)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .background(isSelected ? NowFocusColors.neutral200 : Color.clear)
            .overlay(alignment: .leading) {
                Rectangle().fill(isSelected ? NowFocusColors.accent : Color.clear).frame(width: 3)
            }
            .overlay(alignment: .bottom) { NowFocusRule() }
            .onHover { hover in
                isHovering = hover
            }
            .contextMenu {
                Button("Delete Profile", role: .destructive, action: onDelete)
            }
        }
    }

    @ViewBuilder
    private var detail: some View {
        if let selectedId = selectedPolicyId,
           let index = policies.firstIndex(where: { $0.id == selectedId }) {
            PolicyDetailView(
                policy: $policies[index],
                onSave: { updated in savePolicy(updated) }
            )
        } else {
            VStack(spacing: NowFocusSpace.s2) {
                Text("Select a profile to edit")
                    .font(NowFocusFonts.body(15))
                    .foregroundColor(NowFocusColors.neutral700)
                Text("Or click + to create a new one")
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.neutral700)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func loadPolicies() {
        do {
            policies = try DatabaseManager.shared.fetchAllPolicies()
            // The selected profile may have been deleted on another device.
            if let id = selectedPolicyId, !policies.contains(where: { $0.id == id }) { selectedPolicyId = nil }
            if selectedPolicyId == nil, let first = policies.first {
                selectedPolicyId = first.id
            }
        } catch {
            print("Failed to load policies: \(error)")
        }
    }

    private func addPolicy() {
        let newPolicy = BlockPolicy(name: "New Profile")
        do {
            try DatabaseManager.shared.savePolicy(newPolicy)
            policies.append(newPolicy)
            selectedPolicyId = newPolicy.id
        } catch {
            print("Failed to create policy: \(error)")
        }
    }

    // MARK: - Mid-session enforcement gate

    /// Returns `true` when the edit removes or disables a rule that the stored
    /// version of the policy still has enabled — i.e. the edit weakens
    /// enforcement. Compares by rule `id` so renames/reorders pass through.
    private func weakensEnforcement(old: BlockPolicy, new: BlockPolicy) -> Bool {
        let oldEnabledDomains = Set(old.domains.filter(\.enabled).map(\.id))
        let newEnabledDomains = Set(new.domains.filter(\.enabled).map(\.id))
        if !oldEnabledDomains.isSubset(of: newEnabledDomains) { return true }

        let oldEnabledApps = Set(old.applications.filter(\.enabled).map(\.id))
        let newEnabledApps = Set(new.applications.filter(\.enabled).map(\.id))
        if !oldEnabledApps.isSubset(of: newEnabledApps) { return true }

        // Switching a feed rule off loosens a running session like removing a site does.
        if !Set(old.partial).isSubset(of: Set(new.partial)) { return true }

        return false
    }

    private func savePolicy(_ policy: BlockPolicy) {
        do {
            // Check whether a session is active on this profile.
            let session = try DatabaseManager.shared.fetchActiveSession()
            let isLiveEdit = session.map { s in
                s.policyId == policy.id && SessionEngine().isActive(s)
            } ?? false

            if isLiveEdit {
                // Load the stored (pre-edit) version to detect weakening.
                guard let stored = try DatabaseManager.shared.fetchPolicy(id: policy.id) else {
                    // Profile was deleted out from under us — just save.
                    try DatabaseManager.shared.savePolicy(policy)
                    return
                }

                if weakensEnforcement(old: stored, new: policy) {
                    // Reject: restore the binding so the removed row reappears.
                    if let index = policies.firstIndex(where: { $0.id == policy.id }) {
                        policies[index] = stored
                    }
                    presentCannotDelete(loc("You can\u{2019}t remove blocks while a focus session is running on this profile."))
                    return
                }

                // Pure addition / rename / reorder — save and re-apply.
                try DatabaseManager.shared.savePolicy(policy)
                SessionController.startEnforcement(policy: policy, sessionId: session!.id, endAt: session!.endAt, sessionType: session!.sessionType)
            } else {
                // No active session on this profile — plain save.
                try DatabaseManager.shared.savePolicy(policy)
            }
        } catch {
            print("Failed to save policy: \(error)")
        }
    }

    private func delete(_ policy: BlockPolicy) {
        let alert = NSAlert()
        alert.messageText = loc("Delete Profile")
        alert.informativeText = loc("Are you sure you want to delete the profile \"\(policy.name)\"? This action cannot be undone.")
        alert.alertStyle = .warning
        alert.addButton(withTitle: loc("Delete"))
        alert.addButton(withTitle: loc("Cancel"))
        
        if alert.runModal() == .alertFirstButtonReturn {
            guard let index = policies.firstIndex(where: { $0.id == policy.id }) else { return }
            deletePolicies(at: IndexSet(integer: index))
        }
    }

    private func deletePolicies(at offsets: IndexSet) {
        let idsToDelete = Set(offsets.map { policies[$0].id })
        var deleted = Set<String>()

        for id in idsToDelete where canDelete(id) {
            do {
                try DatabaseManager.shared.deletePolicy(id: id)
                deleted.insert(id)
            } catch {
                print("Failed to delete policy: \(error)")
            }
        }

        // Clear selection before mutating the array, so the detail pane's
        // `$policies[index]` binding is never computed against a stale index.
        if let selectedId = selectedPolicyId, deleted.contains(selectedId) {
            selectedPolicyId = nil
        }

        policies.removeAll { deleted.contains($0.id) }

        if selectedPolicyId == nil {
            selectedPolicyId = policies.first?.id
        }
    }

    /// Ends the live session behind `id` (Normal mode) or refuses deletion
    /// (Strict/Locked), using the same stop-gate as MenuBarView's End Early so a
    /// profile delete can't be used to escape a self-control lock. Returns whether
    /// `id` may be deleted.
    private func canDelete(_ id: String) -> Bool {
        guard let session = try? DatabaseManager.shared.fetchActiveSession(),
              session.policyId == id else { return true }

        switch SessionEngine().stopGate(for: session) {
        case .immediate:
            SessionController.endSession(session, cancelled: true)
            return true
        case .requiresUnlock:
            presentCannotDelete(loc("End this session from the menu bar first, then delete the profile."))
            return false
        case .locked:
            presentCannotDelete(loc("This is a Locked session \u{2014} it can\u{2019}t be ended early. Wait until it finishes, then delete the profile."))
            return false
        }
    }

    private func presentCannotDelete(_ informativeText: String) {
        let alert = NSAlert()
        alert.messageText = loc("Can\u{2019}t delete a profile with an active session")
        alert.informativeText = informativeText
        alert.alertStyle = .warning
        alert.addButton(withTitle: loc("OK"))
        alert.runModal()
    }
}
