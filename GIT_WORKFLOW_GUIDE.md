# Git Workflow Guide for MineSafeAR (Team Collaboration Without Coding)

This guide shows you and your friend how to set up Git, push your project to GitHub/GitLab, and work simultaneously on two laptops without merge conflicts.

---

## 🚀 Step 1: Push the Project to Git (Initial Setup)

Run these commands on **Laptop 1** in the terminal inside `/Users/basanirajrao/MineSafeAR`:

```bash
git init
git add .
git commit -m "Initial commit: MineSafeAR divided into Simulation and Certification modules"
git branch -M main
git remote add origin <YOUR_GITHUB_REPOSITORY_URL>
git push -u origin main
```

---

## 💻 Step 2: Set Up Both Laptops on Separate Branches

### **Laptop 1 (Developer A - AR Simulation & Drills)**
In the terminal on Laptop 1:
```bash
git checkout -b feature/simulation
```
> **When prompting Gemini on Laptop 1:** Always copy and paste the prompt inside `DEVELOPER_A_GEMINI_INSTRUCTIONS.md` at the start of your conversation.

---

### **Laptop 2 (Developer B - Certification & Sync)**
On Laptop 2, clone the repository:
```bash
git clone <YOUR_GITHUB_REPOSITORY_URL>
cd MineSafeAR
git checkout -b feature/management
```
> **When prompting Gemini on Laptop 2:** Always copy and paste the prompt inside `DEVELOPER_B_GEMINI_INSTRUCTIONS.md` at the start of your conversation.

---

## 🔄 Step 3: Daily Workflow (Pushing & Merging Changes)

Whenever either of you finishes a feature:

1. **Commit and Push your branch:**
   ```bash
   git add .
   git commit -m "Added new feature in my module"
   git push origin <your-branch-name>
   ```

2. **Merge into `main`:**
   - Go to GitHub/GitLab and click **Create Pull Request / Merge Request**.
   - Merge `feature/simulation` or `feature/management` into `main`.
   - Because you are working on separate files, separate navigation graphs (`SimulationNavGraph.kt` vs `ManagementNavGraph.kt`), and separate string files (`strings_simulation.xml` vs `strings_management.xml`), **Git will merge automatically with ZERO conflicts!**

3. **Get the latest changes on your laptop:**
   ```bash
   git checkout main
   git pull origin main
   git checkout <your-branch-name>
   git rebase main
   ```
