package org.ruyisdk.venv.views;

import java.util.List;
import org.eclipse.core.databinding.DataBindingContext;
import org.eclipse.core.databinding.UpdateValueStrategy;
import org.eclipse.core.databinding.beans.typed.BeanProperties;
import org.eclipse.core.databinding.conversion.Converter;
import org.eclipse.jface.databinding.viewers.typed.ViewerProperties;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.ruyisdk.venv.model.Toolchain;
import org.ruyisdk.venv.viewmodel.VenvWizardViewModel;

/**
 * Dialog for selecting a toolchain package and version to use as a sysroot source.
 *
 * <p>
 * Displays a name list and a version list side-by-side, identical to the toolchain selection tables
 * on the wizard configuration page.
 */
class SysrootPackageSelectionDialog extends Dialog {

    private final VenvWizardViewModel viewModel;
    private DataBindingContext dbc;

    private TableViewer packageNamesViewer;
    private TableViewer packageVersionsViewer;

    SysrootPackageSelectionDialog(Shell parentShell, VenvWizardViewModel viewModel) {
        super(parentShell);
        this.viewModel = viewModel;
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Select the Source Package for Sysroot");
    }

    @Override
    protected Point getInitialSize() {
        return new Point(600, 400);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        final var container = (Composite) super.createDialogArea(parent);
        final var gridLayout = new GridLayout(2, false);
        container.setLayout(gridLayout);

        final var nameLabel = new Label(container, SWT.NONE);
        nameLabel.setText("Package");
        final var versionLabel = new Label(container, SWT.NONE);
        versionLabel.setText("Version");

        packageNamesViewer = new TableViewer(container, SWT.BORDER | SWT.FULL_SELECTION);
        {
            final var table = packageNamesViewer.getTable();
            {
                final var gridData = new GridData(SWT.FILL, SWT.FILL, true, true);
                gridData.widthHint = 280;
                table.setLayoutData(gridData);
            }
            table.setHeaderVisible(false);
            table.setLinesVisible(true);
        }
        packageNamesViewer.setContentProvider(ArrayContentProvider.getInstance());
        packageNamesViewer.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((Toolchain) element).getName();
            }
        });
        packageNamesViewer.setInput(viewModel.getSysrootToolchains());

        packageVersionsViewer = new TableViewer(container, SWT.BORDER | SWT.FULL_SELECTION);
        {
            final var table = packageVersionsViewer.getTable();
            {
                final var gridData = new GridData(SWT.FILL, SWT.FILL, true, true);
                gridData.widthHint = 280;
                table.setLayoutData(gridData);
            }
            table.setHeaderVisible(false);
            table.setLinesVisible(true);
        }
        packageVersionsViewer.setContentProvider(ArrayContentProvider.getInstance());
        packageVersionsViewer.setLabelProvider(new ColumnLabelProvider());

        dbc = new DataBindingContext();

        container.addDisposeListener(e -> {
            if (dbc != null) {
                dbc.dispose();
                dbc = null;
            }
        });

        // package names
        {
            final var packageSelection =
                    ViewerProperties.singleSelection(Toolchain.class).observe(packageNamesViewer);
            final var packageIndexObservable = BeanProperties
                    .value(VenvWizardViewModel.class, "selectedSysrootPackageIndex", Integer.class)
                    .observe(viewModel);
            final var packageToIndex = new UpdateValueStrategy<Toolchain, Integer>();
            packageToIndex.setConverter(
                    new Converter<Toolchain, Integer>(Toolchain.class, Integer.class) {
                        @Override
                        public Integer convert(Toolchain fromObject) {
                            if (fromObject == null) {
                                return Integer.valueOf(-1);
                            }
                            return Integer
                                    .valueOf(viewModel.getSysrootToolchains().indexOf(fromObject));
                        }
                    });
            final var indexToToolchain = new UpdateValueStrategy<Integer, Toolchain>();
            indexToToolchain.setConverter(
                    new Converter<Integer, Toolchain>(Integer.class, Toolchain.class) {
                        @Override
                        public Toolchain convert(Integer fromObject) {
                            final var idx = ((Integer) fromObject).intValue();
                            final var toolchains = viewModel.getSysrootToolchains();
                            if (idx >= 0 && idx < toolchains.size()) {
                                return toolchains.get(idx);
                            }
                            return null;
                        }
                    });
            dbc.bindValue(packageSelection, packageIndexObservable, packageToIndex,
                    indexToToolchain);

            packageIndexObservable.addValueChangeListener(e -> {
                updatePackagesVersions();
            });
        }

        // package versions
        {
            final var packageVersionSelection =
                    ViewerProperties.singleSelection(String.class).observe(packageVersionsViewer);
            final var packageVersionIndexObservable =
                    BeanProperties.value(VenvWizardViewModel.class,
                            "selectedSysrootPackageVersionIndex", Integer.class).observe(viewModel);
            final var packageVersionToIndex = new UpdateValueStrategy<String, Integer>();
            packageVersionToIndex
                    .setConverter(new Converter<String, Integer>(String.class, Integer.class) {
                        @Override
                        public Integer convert(String fromObject) {
                            final var idx = viewModel.getSelectedSysrootPackageIndex();
                            final var toolchains = viewModel.getSysrootToolchains();
                            if (idx < 0 || idx >= toolchains.size()) {
                                return Integer.valueOf(-1);
                            }
                            final var vers = toolchains.get(idx).getVersions();
                            return Integer.valueOf(vers == null ? -1 : vers.indexOf(fromObject));
                        }
                    });
            final var indexToToolchainVersion = new UpdateValueStrategy<Integer, String>();
            indexToToolchainVersion
                    .setConverter(new Converter<Integer, String>(Integer.class, String.class) {
                        @Override
                        public String convert(Integer fromObject) {
                            final var idx = viewModel.getSelectedSysrootPackageIndex();
                            final var toolchains = viewModel.getSysrootToolchains();
                            if (idx < 0 || idx >= toolchains.size()) {
                                return null;
                            }
                            final var vers = toolchains.get(idx).getVersions();
                            final var verIdx = ((Integer) fromObject).intValue();
                            return vers != null && verIdx >= 0 && verIdx < vers.size()
                                    ? vers.get(verIdx)
                                    : null;
                        }
                    });
            dbc.bindValue(packageVersionSelection, packageVersionIndexObservable,
                    packageVersionToIndex, indexToToolchainVersion);
        }

        // initial states
        updatePackagesVersions();

        return container;
    }

    private void updatePackagesVersions() {
        if (packageVersionsViewer == null || packageVersionsViewer.getControl() == null
                || packageVersionsViewer.getControl().isDisposed()) {
            return;
        }
        final var selectedPackageIndex = viewModel.getSelectedSysrootPackageIndex();
        final var toolchains = viewModel.getSysrootToolchains();
        if (selectedPackageIndex >= 0 && selectedPackageIndex < toolchains.size()) {
            final var toolchain = toolchains.get(selectedPackageIndex);
            packageVersionsViewer.setInput(toolchain.getVersions());

            final var selectedVersionIndex = viewModel.getSelectedSysrootPackageVersionIndex();
            if (selectedVersionIndex >= 0 && toolchain.getVersions() != null
                    && selectedVersionIndex < toolchain.getVersions().size()) {
                packageVersionsViewer.getTable().select(selectedVersionIndex);
                packageVersionsViewer.getTable().showSelection();
            }
        } else {
            packageVersionsViewer.setInput(List.of());
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.CLOSE_ID, IDialogConstants.CLOSE_LABEL, true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == IDialogConstants.CLOSE_ID) {
            okPressed();
        }
    }
}
