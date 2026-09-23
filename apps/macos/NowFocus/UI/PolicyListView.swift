import SwiftUI
import NowFocusCore

struct PolicyListView: View {
    @State private var policies: [BlockPolicy] = []
    @State private var selectedPolicyId: String?
    
    var body: some View {
        NavigationSplitView {
            List(selection: $selectedPolicyId) {
                ForEach(policies) { policy in
                    NavigationLink(value: policy.id) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(policy.name)
                                .font(.headline)
                            Text("\(policy.domains.count) sites · \(policy.applications.count) apps")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                    .contextMenu {
                        Button("Delete Policy", role: .destructive) {
                            delete(policy)
                        }
                    }
                }
                .onDelete(perform: deletePolicies)
            }
            .listStyle(.sidebar)
            .frame(minWidth: 180)
            .toolbar {
                ToolbarItem {
                    Button(action: addPolicy) {
                        Label("Add Policy", systemImage: "plus")
                    }
                }
            }
        } detail: {
            if let selectedId = selectedPolicyId,
               let index = policies.firstIndex(where: { $0.id == selectedId }) {
                PolicyDetailView(
                    policy: $policies[index],
                    onSave: { updated in
                        savePolicy(updated)
                    }
                )
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "shield.lefthalf.filled")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text("Select a policy to edit")
                        .font(.title3)
                        .foregroundColor(.secondary)
                    Text("Or click + to create a new one")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
        .onAppear { loadPolicies() }
    }
    
    private func loadPolicies() {
        do {
            policies = try DatabaseManager.shared.fetchAllPolicies()
            // Auto-select the first policy if nothing is selected
            if selectedPolicyId == nil, let first = policies.first {
                selectedPolicyId = first.id
            }
        } catch {
            print("Failed to load policies: \(error)")
        }
    }
    
    private func addPolicy() {
        let newPolicy = BlockPolicy(name: "New Policy")
        do {
            try DatabaseManager.shared.savePolicy(newPolicy)
            policies.append(newPolicy)
            selectedPolicyId = newPolicy.id
        } catch {
            print("Failed to create policy: \(error)")
        }
    }
    
    private func savePolicy(_ policy: BlockPolicy) {
        do {
            try DatabaseManager.shared.savePolicy(policy)
        } catch {
            print("Failed to save policy: \(error)")
        }
    }
    
    private func delete(_ policy: BlockPolicy) {
        guard let index = policies.firstIndex(where: { $0.id == policy.id }) else { return }
        deletePolicies(at: IndexSet(integer: index))
    }

    private func deletePolicies(at offsets: IndexSet) {
        let idsToDelete = Set(offsets.map { policies[$0].id })

        // Clear selection before mutating the array, so the detail pane's
        // `$policies[index]` binding is never computed against a stale index.
        if let selectedId = selectedPolicyId, idsToDelete.contains(selectedId) {
            selectedPolicyId = nil
        }

        for id in idsToDelete {
            do {
                try DatabaseManager.shared.deletePolicy(id: id)
            } catch {
                print("Failed to delete policy: \(error)")
            }
        }

        policies.remove(atOffsets: offsets)

        if selectedPolicyId == nil {
            selectedPolicyId = policies.first?.id
        }
    }
}
